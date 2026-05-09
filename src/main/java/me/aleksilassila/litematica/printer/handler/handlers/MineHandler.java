package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

public class MineHandler extends ClientPlayerTickHandler {
    public static final String NAME = "mine";

    private final ArrayDeque<BlockPos> retryQueue = new ArrayDeque<>();
    private final Set<BlockPos> retryTargets = new HashSet<>();
    private int preprocessEffectiveExecutions;
    private int remainingMainScanExecutions;

    public MineHandler() {
        super(NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
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
        return this.remainingMainScanExecutions;
    }

    @Override
    protected void preprocess() {
        this.preprocessEffectiveExecutions = 0;
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        this.remainingMainScanExecutions = configuredBudget == 0 ? -1 : configuredBudget;

        if (this.level == null || this.player == null) {
            this.retryQueue.clear();
            this.retryTargets.clear();
            return;
        }

        this.pruneRetryTargets();
        this.processRetryTargets();

        if (this.remainingMainScanExecutions > 0) {
            this.remainingMainScanExecutions = Math.max(0, this.remainingMainScanExecutions - this.preprocessEffectiveExecutions);
        }
    }

    @Override
    protected boolean canIterate() {
        return this.remainingMainScanExecutions != 0;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        return !CooldownUtils.INSTANCE.isOnCooldown(level, FluidHandler.NAME, pos)
                && InteractionUtils.canBreakBlock(pos)
                && mineRestriction(level.getBlockState(pos));
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        boolean consumed = this.handleMineAttempt(blockPos);
        if (!consumed) {
            this.setIterationConsumedEffectiveExecution(false);
        }
    }

    private void processRetryTargets() {
        int retryBudget = this.getRetryBudget();
        if (retryBudget <= 0 || this.retryQueue.isEmpty()) {
            return;
        }

        int available = this.retryQueue.size();
        while (available-- > 0 && this.preprocessEffectiveExecutions < retryBudget) {
            BlockPos pos = this.pollRetryTarget();
            if (pos == null) {
                break;
            }
            if (!this.canRetryTarget(pos)) {
                continue;
            }
            if (this.handleMineAttempt(pos)) {
                this.preprocessEffectiveExecutions++;
            }
        }
    }

    private int getRetryBudget() {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        if (configuredBudget < 0) {
            return -1;
        }
        if (configuredBudget == 0) {
            return this.retryQueue.size();
        }
        int preferredBudget = Math.max(1, configuredBudget / 2);
        int maxRetryBudget = configuredBudget > 1 ? configuredBudget - 1 : configuredBudget;
        return Math.min(this.retryQueue.size(), Math.min(preferredBudget, maxRetryBudget));
    }

    private boolean canRetryTarget(BlockPos pos) {
        if (this.level == null || this.player == null || pos == null) {
            return false;
        }
        if (!this.canReachIterationPosition(pos)) {
            return false;
        }
        if (!LitematicaUtils.isWithinSelection1ModeRange(pos)) {
            return false;
        }
        if (!ConfigUtils.isPositionInSelectionRange(this.player, pos, Configs.Mine.MINE_SELECTION_TYPE)) {
            return false;
        }
        return this.canIterationBlockPos(pos);
    }

    private void pruneRetryTargets() {
        Iterator<BlockPos> iterator = this.retryQueue.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = iterator.next();
            if (!this.canRetryTarget(pos)) {
                iterator.remove();
                this.retryTargets.remove(pos);
            }
        }
    }

    private boolean handleMineAttempt(BlockPos blockPos) {
        BlockBreakResult result = InteractionUtils.INSTANCE.continueDestroyBlockForMine(blockPos);
        return this.handleMineResult(blockPos, result);
    }

    private boolean handleMineResult(BlockPos blockPos, BlockBreakResult result) {
        return switch (result) {
            case COMPLETED -> {
                this.retryTargets.remove(blockPos);
                yield true;
            }
            case COMPLETED_WAIT, IN_PROGRESS, ABORTED -> {
                this.enqueueRetryTarget(blockPos);
                yield true;
            }
            case FAILED -> {
                this.retryTargets.remove(blockPos);
                yield false;
            }
        };
    }

    private void enqueueRetryTarget(BlockPos pos) {
        if (pos == null || this.retryTargets.contains(pos)) {
            return;
        }
        BlockPos immutablePos = pos.immutable();
        this.retryQueue.addLast(immutablePos);
        this.retryTargets.add(immutablePos);

        int maxRetryTargets = this.getMaxRetryTargets();
        while (this.retryQueue.size() > maxRetryTargets) {
            BlockPos removed = this.retryQueue.pollFirst();
            if (removed != null) {
                this.retryTargets.remove(removed);
            }
        }
    }

    private BlockPos pollRetryTarget() {
        BlockPos pos = this.retryQueue.pollFirst();
        if (pos != null) {
            this.retryTargets.remove(pos);
        }
        return pos;
    }

    private int getMaxRetryTargets() {
        int configuredBudget = Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
        if (configuredBudget <= 0) {
            return 512;
        }
        return Math.max(128, configuredBudget * 16);
    }
}
