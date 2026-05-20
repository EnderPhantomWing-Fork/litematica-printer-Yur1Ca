package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

public class MineHandler extends ClientPlayerTickHandler {
    public static final String NAME = "mine";
    private final MineBreakExecutor analyzer = new MineBreakExecutor();
    private final List<MineBreakExecutor.Target> candidates = new ArrayList<>();
    @Nullable
    private BlockPos activeMinePos;
    private int remainingInstantBudget;
    private int toolSessionRemaining;

    public MineHandler() {
        super(NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    @Override
    public void tick() {
        if (!ConfigUtils.isEnable() || !ConfigUtils.isMineMode()) {
            this.analyzer.reset();
            this.activeMinePos = null;
            this.toolSessionRemaining = 0;
        }
        super.tick();
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
            if (listType == UsageRestriction.ListType.BLACKLIST) {
                return BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else if (listType == UsageRestriction.ListType.WHITELIST) {
                return BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings().stream()
                        .anyMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        } else {
            IConfigOptionListEntry optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
            if (optionListValue == UsageRestriction.ListType.BLACKLIST) {
                return Configs.Mine.EXCAVATE_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else if (optionListValue == UsageRestriction.ListType.WHITELIST) {
                return Configs.Mine.EXCAVATE_WHITELIST.getStrings().stream()
                        .anyMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        }
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
        return !CooldownUtils.INSTANCE.isOnCooldown(level, FluidHandler.NAME, pos)
                && !InteractionUtils.INSTANCE.isRecentlyBroken(pos)
                && !InteractionUtils.INSTANCE.isPendingDelayedDestroy(pos)
                && InteractionUtils.canBreakBlock(pos)
                && mineRestriction(level.getBlockState(pos));
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
        if (ActionManager.INSTANCE.needWaitModifyLook
                || this.activeMinePos != null) {
            return;
        }
        if (this.toolSessionRemaining > 0) {
            if (this.executeCurrentToolFastBatch(-1)) {
                return;
            }
            MineBreakExecutor.Target currentToolContinuousCandidate = this.selectCurrentToolContinuousCandidate();
            if (currentToolContinuousCandidate != null) {
                this.startContinuous(currentToolContinuousCandidate, false);
                return;
            }
            this.toolSessionRemaining = 0;
        }

        MineBreakExecutor.Target switchToolInstantCandidate = this.selectSwitchToolInstantCandidate();
        boolean currentToolFastExecuted = this.executeCurrentToolFastBatch(this.getCurrentToolFastBudget(switchToolInstantCandidate != null));
        if (this.activeMinePos != null) {
            return;
        }
        if (switchToolInstantCandidate != null && this.executeSwitchedToolFastBatch(switchToolInstantCandidate)) {
            return;
        }
        if (currentToolFastExecuted) {
            return;
        }
        MineBreakExecutor.Target currentToolContinuousCandidate = this.selectCurrentToolContinuousCandidate();
        MineBreakExecutor.Target switchToolContinuousCandidate = this.selectSwitchToolContinuousCandidate();
        if (currentToolContinuousCandidate != null && switchToolContinuousCandidate != null) {
            this.startContinuous(switchToolContinuousCandidate, true);
            return;
        }
        if (currentToolContinuousCandidate != null) {
            this.startContinuous(currentToolContinuousCandidate, false);
            return;
        }
        if (switchToolContinuousCandidate != null) {
            this.startContinuous(switchToolContinuousCandidate, true);
        }
    }

    private void startContinuous(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch);
        this.startToolSessionIfUseful(result);
        this.handleMineResult(target.pos(), result);
        this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
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
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(pos, Direction.DOWN, false);
        this.handleMineResult(pos, result);
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

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

    private boolean executeCurrentToolFastBatch(int maxActions) {
        boolean executed = false;
        for (MineBreakExecutor.Target target : this.candidates) {
            if (!this.hasInstantBudget() || maxActions == 0) {
                break;
            }
            if (!this.analyzer.isInstantWithCurrentTool(target)) {
                continue;
            }
            BlockBreakResult result = this.executeMineTarget(target, false);
            this.consumeInstantBudget();
            this.handleMineResult(target.pos(), result);
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
            executed = true;
            this.consumeToolSessionAction();
            if (maxActions > 0) {
                maxActions--;
            }
            if (result == BlockBreakResult.IN_PROGRESS || this.activeMinePos != null) {
                break;
            }
        }
        return executed;
    }

    private boolean executeSwitchedToolFastBatch(MineBreakExecutor.Target firstTarget) {
        if (!this.hasInstantBudget()) {
            return false;
        }
        BlockBreakResult result = this.executeMineTarget(firstTarget, true);
        this.consumeInstantBudget();
        this.startToolSessionIfUseful(result);
        this.handleMineResult(firstTarget.pos(), result);
        this.setBlockPosCooldown(firstTarget.pos(), ConfigUtils.getBreakCooldown());
        if (result == BlockBreakResult.IN_PROGRESS || this.activeMinePos != null) {
            return true;
        }
        this.executeCurrentToolFastBatchFromFreshAnalysis(firstTarget.pos());
        return true;
    }

    private void executeCurrentToolFastBatchFromFreshAnalysis(BlockPos skippedPos) {
        for (MineBreakExecutor.Target candidate : this.candidates) {
            if (!this.hasInstantBudget()) {
                break;
            }
            if (candidate.pos().equals(skippedPos)) {
                continue;
            }
            MineBreakExecutor.Target target = this.analyzer.analyze(candidate.pos());
            if (target == null || !this.analyzer.isInstantWithCurrentTool(target)) {
                continue;
            }
            BlockBreakResult result = this.executeMineTarget(target, false);
            this.consumeInstantBudget();
            this.consumeToolSessionAction();
            this.handleMineResult(target.pos(), result);
            this.setBlockPosCooldown(target.pos(), ConfigUtils.getBreakCooldown());
            if (result == BlockBreakResult.IN_PROGRESS || this.activeMinePos != null) {
                break;
            }
        }
    }

    private int getCurrentToolFastBudget(boolean reserveForSwitchTool) {
        if (!reserveForSwitchTool || this.remainingInstantBudget < 0) {
            return -1;
        }
        if (this.remainingInstantBudget <= 1) {
            return this.remainingInstantBudget;
        }
        return Math.max(1, this.remainingInstantBudget / 2);
    }

    private void startToolSession() {
        this.toolSessionRemaining = Math.max(this.toolSessionRemaining, this.getToolSessionQuota());
    }

    private void startToolSessionIfUseful(BlockBreakResult result) {
        if (result == BlockBreakResult.IN_PROGRESS || result == BlockBreakResult.COMPLETED_WAIT) {
            this.startToolSession();
            this.consumeToolSessionAction();
        }
    }

    private void consumeToolSessionAction() {
        if (this.toolSessionRemaining > 0) {
            this.toolSessionRemaining--;
        }
    }

    private int getToolSessionQuota() {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        if (configuredBudget <= 0) {
            return 12;
        }
        return Math.max(4, Math.min(12, configuredBudget / 2));
    }

    private MineBreakExecutor.Target selectSwitchToolInstantCandidate() {
        MineBreakExecutor.Target selected = null;
        for (MineBreakExecutor.Target target : this.candidates) {
            if (this.analyzer.isInstantWithCurrentTool(target) || !this.analyzer.isInstantWithBestTool(target)) {
                continue;
            }
            if (selected == null || target.progress() > selected.progress()) {
                selected = target;
            }
        }
        return selected;
    }

    private MineBreakExecutor.Target selectCurrentToolContinuousCandidate() {
        MineBreakExecutor.Target selected = null;
        for (MineBreakExecutor.Target target : this.candidates) {
            if (!this.analyzer.canUseCurrentTool(target)) {
                continue;
            }
            if (selected == null || target.currentProgress() > selected.currentProgress()) {
                selected = target;
            }
        }
        return selected;
    }

    private MineBreakExecutor.Target selectSwitchToolContinuousCandidate() {
        MineBreakExecutor.Target selected = null;
        for (MineBreakExecutor.Target target : this.candidates) {
            if (!this.analyzer.canUseBetterTool(target)) {
                continue;
            }
            if (selected == null || target.progress() > selected.progress()) {
                selected = target;
            }
        }
        return selected;
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
}
