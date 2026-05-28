package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

public class MineHandler extends Module {
    public static final String NAME = "mine";
    private static final double TOOL_SESSION_FRONTIER_MARGIN = 2.5D;
    private static final RestrictionCache MINE_RESTRICTION_CACHE = new RestrictionCache();

    private final MineBreakExecutor analyzer = new MineBreakExecutor();
    private final List<MineBreakExecutor.Target> candidates = new ArrayList<>();
    @Nullable
    private BlockPos activeMinePos;
    @Nullable
    private Item sessionToolItem;
    private int remainingInstantBudget;
    private int toolSessionRemaining;

    public MineHandler() {
        super(NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    @Override
    public void tick() {
        this.tick(TickContext.capture());
    }

    @Override
    public void tick(TickContext context) {
        if (!ConfigUtils.isEnable() || !ConfigUtils.isMineMode()) {
            this.analyzer.reset();
            this.activeMinePos = null;
            this.sessionToolItem = null;
            this.toolSessionRemaining = 0;
        }
        super.tick(context);
    }

    public int getRetryQueueSize() {
        return this.activeMinePos == null ? 0 : 1;
    }

    public static boolean mineRestriction(BlockState blockState) {
        if (!InteractionUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            if (!ModLoadUtils.isTweakerooLoaded()) return true;
            UsageRestriction.ListType listType = BLOCK_TYPE_BREAK_RESTRICTION.getListType();
            List<String> filters = listType == UsageRestriction.ListType.BLACKLIST
                    ? BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings()
                    : listType == UsageRestriction.ListType.WHITELIST
                    ? BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings()
                    : List.of();
            return MINE_RESTRICTION_CACHE.allows("tweakeroo", listType, filters, blockState);
        }

        Object optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
        UsageRestriction.ListType listType = optionListValue instanceof UsageRestriction.ListType type
                ? type
                : UsageRestriction.ListType.NONE;
        List<String> filters = listType == UsageRestriction.ListType.BLACKLIST
                ? Configs.Mine.EXCAVATE_BLACKLIST.getStrings()
                : listType == UsageRestriction.ListType.WHITELIST
                ? Configs.Mine.EXCAVATE_WHITELIST.getStrings()
                : List.of();
        return MINE_RESTRICTION_CACHE.allows("custom", listType, filters, blockState);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return 0;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        return this.getFilteredIterationPositions(playerInteractionBox, this::isMineScanCandidate);
    }

    @Override
    protected void preprocess() {
        this.candidates.clear();
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        this.remainingInstantBudget = configuredBudget == 0 ? -1 : configuredBudget;
        this.continueActiveMineTarget();
    }

    @Override
    protected boolean canIterate() {
        return this.activeMinePos == null && !InteractionUtils.INSTANCE.hasActiveDestroyTarget();
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        return this.isMineScanCandidate(pos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        MineBreakExecutor.Target target = this.analyzer.analyze(blockPos);
        if (target == null) {
            this.setIterationConsumedEffectiveExecution(false);
            return;
        }
        this.candidates.add(target);
        this.setIterationConsumedEffectiveExecution(false);
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (ActionManager.INSTANCE.needWaitModifyLook || this.activeMinePos != null || this.candidates.isEmpty()) {
            return;
        }
        this.candidates.sort(this.createTargetComparator());
        MineBreakExecutor.Target nearest = this.candidates.get(0);
        MineBreakExecutor.Target selected = this.selectTargetForToolSession(nearest);
        this.executeToolSession(selected, this.distanceScore(nearest));
    }

    private void continueActiveMineTarget() {
        BlockPos pos = this.activeMinePos;
        if (pos == null) {
            return;
        }
        if (!this.canContinueActiveMineTarget(pos)) {
            this.activeMinePos = null;
            return;
        }
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(pos, Direction.DOWN, true);
        this.handleMineResult(pos, result);
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.consumeToolSessionAction();
        }
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = null;
            this.setBlockPosCooldown(pos, ConfigUtils.getBreakCooldown());
        }
    }

    private boolean canContinueActiveMineTarget(BlockPos pos) {
        return pos != null
                && this.canReachIterationPosition(pos)
                && InteractionUtils.canBreakBlock(pos)
                && mineRestriction(this.level.getBlockState(pos));
    }

    private boolean isMineScanCandidate(BlockPos pos) {
        if (pos == null || this.level == null || this.player == null || this.gameMode == null) {
            return false;
        }

        if (this.isBlockPosOnCooldown(pos)
                || InteractionUtils.INSTANCE.isRecentlyBroken(pos)
                || InteractionUtils.INSTANCE.isPendingDelayedDestroy(pos)) {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }

        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && state.getBlock().defaultDestroyTime() < 0) {
            return false;
        }

        return this.canReachIterationPosition(pos)
                && !this.player.blockActionRestricted(this.level, pos, this.gameMode.getPlayerMode())
                && mineRestriction(state);
    }

    private Comparator<MineBreakExecutor.Target> createTargetComparator() {
        return Comparator
                .comparingDouble(this::distanceScore)
                .thenComparingInt(target -> target.pos().getY())
                .thenComparingInt(target -> target.pos().getX())
                .thenComparingInt(target -> target.pos().getZ());
    }

    private double distanceScore(MineBreakExecutor.Target target) {
        if (this.player == null) {
            return 0.0D;
        }
        Vec3 eye = this.player.getEyePosition();
        return Vec3.atCenterOf(target.pos()).distanceToSqr(eye);
    }

    private MineBreakExecutor.Target selectTargetForToolSession(MineBreakExecutor.Target nearest) {
        if (this.sessionToolItem != null && this.toolSessionRemaining > 0) {
            double nearestDistance = this.distanceScore(nearest);
            for (MineBreakExecutor.Target target : this.candidates) {
                if (!this.isInsideToolSessionFrontier(target, nearestDistance)) {
                    break;
                }
                if (this.analyzer.hasSameBestTool(target, this.sessionToolItem)) {
                    return target;
                }
            }
        }
        this.sessionToolItem = nearest.bestToolItem();
        this.toolSessionRemaining = this.getToolSessionQuota();
        return nearest;
    }

    private boolean isInsideToolSessionFrontier(MineBreakExecutor.Target target, double nearestDistance) {
        if (this.remainingInstantBudget < 0) {
            return true;
        }
        double nearest = Math.sqrt(nearestDistance);
        double targetDistance = Math.sqrt(this.distanceScore(target));
        return targetDistance <= nearest + TOOL_SESSION_FRONTIER_MARGIN;
    }

    private void executeToolSession(MineBreakExecutor.Target firstTarget, double nearestDistance) {
        Item toolItem = firstTarget.bestToolItem();
        this.sessionToolItem = toolItem;
        if (this.toolSessionRemaining <= 0) {
            this.toolSessionRemaining = this.getToolSessionQuota();
        }
        BlockBreakResult result = this.executeSessionTarget(firstTarget, !this.analyzer.isCurrentToolEffective(firstTarget));
        if (this.shouldStopSession(result)) {
            return;
        }
        for (MineBreakExecutor.Target target : this.candidates) {
            if (target == firstTarget) {
                continue;
            }
            if (!this.hasInstantBudget()) {
                break;
            }
            if (!this.analyzer.hasSameBestTool(target, toolItem)) {
                continue;
            }
            if (!this.isInsideToolSessionFrontier(target, nearestDistance)) {
                break;
            }
            result = this.executeSessionTarget(target, false);
            if (this.shouldStopSession(result)) {
                break;
            }
        }
    }

    private BlockBreakResult executeSessionTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch);
        if (result != BlockBreakResult.FAILED) {
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.consumeInstantBudget();
        }
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.consumeToolSessionAction();
        }
        this.handleMineResult(target.pos(), result);
        return result;
    }

    private boolean shouldStopSession(BlockBreakResult result) {
        return result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.ABORTED
                || this.activeMinePos != null
                || !this.hasInstantBudget();
    }

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

    private void consumeToolSessionAction() {
        if (this.toolSessionRemaining > 0) {
            this.toolSessionRemaining--;
        }
    }

    private int getToolSessionQuota() {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        if (configuredBudget <= 0) {
            return 8;
        }
        return Math.max(3, Math.min(8, configuredBudget / 2));
    }

    private boolean hasInstantBudget() {
        return this.remainingInstantBudget < 0 || this.remainingInstantBudget > 0;
    }

    private void consumeInstantBudget() {
        if (this.remainingInstantBudget > 0) {
            this.remainingInstantBudget--;
        }
    }

    private void handleMineResult(BlockPos blockPos, BlockBreakResult result) {
        switch (result) {
            case COMPLETED -> {
                InteractionUtils.INSTANCE.markRecentlyBroken(blockPos);
                HudStatsManager.INSTANCE.trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.MINE, 1);
                HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.MINE, "运行中");
            }
            case COMPLETED_WAIT -> {
                InteractionUtils.INSTANCE.markRecentlyBroken(blockPos);
                InteractionUtils.INSTANCE.markPendingBroken(blockPos, ConfigUtils.getBreakCooldown());
                HudStatsManager.INSTANCE.trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.MINE, "等待服务端确认");
            }
            case IN_PROGRESS -> {
                HudStatsManager.INSTANCE.trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.MINE, "破坏中");
            }
            case ABORTED -> {
                HudStatsManager.INSTANCE.trackExpectedMineClear(HudStatsManager.Mode.MINE, blockPos);
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.MINE, "挖掘中断");
            }
            case FAILED -> HudStatsManager.INSTANCE.recordFailure(HudStatsManager.Mode.MINE, "破坏失败");
        }
    }

    private static final class RestrictionCache {
        private String source = "";
        private UsageRestriction.ListType listType = UsageRestriction.ListType.NONE;
        private List<String> listCache = List.of();
        private String[] filters = new String[0];

        private boolean allows(String source, UsageRestriction.ListType listType, List<String> filters, BlockState blockState) {
            this.update(source, listType, filters);
            if (this.listType == UsageRestriction.ListType.BLACKLIST) {
                return !this.matchesAny(blockState);
            }
            if (this.listType == UsageRestriction.ListType.WHITELIST) {
                return this.matchesAny(blockState);
            }
            return true;
        }

        private void update(String source, UsageRestriction.ListType listType, List<String> filters) {
            if (source.equals(this.source) && listType == this.listType && filters.equals(this.listCache)) {
                return;
            }
            this.source = source;
            this.listType = listType;
            this.listCache = new ArrayList<>(filters);
            this.filters = this.listCache.toArray(new String[0]);
        }

        private boolean matchesAny(BlockState blockState) {
            for (String filter : this.filters) {
                if (FilterUtils.matchBlockName(filter, blockState)) {
                    return true;
                }
            }
            return false;
        }
    }
}
