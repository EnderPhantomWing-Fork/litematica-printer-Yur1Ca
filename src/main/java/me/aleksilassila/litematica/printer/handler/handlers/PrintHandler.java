package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskAction;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskController;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.*;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

public class PrintHandler extends Module {
    public final static String NAME = "print";

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;
    @Nullable
    private PrintTaskAction printTaskAction;

    private SchematicBlockContext ctx;
    private final PrintTaskController printTasks = new PrintTaskController();

    private final Deque<BlockPos> sortedTargetQueue = new ArrayDeque<>();
    private PrinterBox sortedTargetBox;
    private boolean hasMoreSortedIterationPositions;
    private List<String> printSkipListCache = List.of();
    private String[] printSkipFilters = new String[0];

    public PrintHandler() {
        super(NAME, PrintModeType.PRINTER, Configs.Core.PRINT, Configs.Print.PRINT_SELECTION_TYPE, true);
    }

    public SchematicBlockContext getContext() {
        return ctx;
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean isSchematicBlockHandler() {
        return true;
    }

    @Override
    protected void preprocess() {
        this.updatePrintSkipCache();
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        BlockPos activeTaskPos = this.printTasks.getActiveTargetPos(level, schematic);
        if (activeTaskPos != null) {
            this.sortedTargetQueue.clear();
            this.sortedTargetBox = null;
            this.hasMoreSortedIterationPositions = false;
            return List.of(activeTaskPos);
        }
        if (!Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()) {
            this.sortedTargetQueue.clear();
            this.sortedTargetBox = null;
            return playerInteractionBox;
        }
        if (schematic == null || player == null) {
            this.sortedTargetQueue.clear();
            this.sortedTargetBox = null;
            return playerInteractionBox;
        }
        if (this.sortedTargetBox != playerInteractionBox) {
            this.sortedTargetQueue.clear();
            this.sortedTargetBox = playerInteractionBox;
        }
        fillSortedTargetQueue(playerInteractionBox, schematic);
        return this::createSortedTargetIterator;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        this.action = null;
        this.printTaskAction = null;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
        if (InteractionUtils.INSTANCE.isRecentlyBroken(blockPos) && !this.printTasks.isActiveTaskPos(blockPos)) {
            return false;
        }
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (this.shouldSkipRequiredState(ctx.requiredState)) {
            return false;
        }
        PrintTaskBuildResult taskResult = this.printTasks.buildAction(ctx);
        if (taskResult.handled()) {
            if (!taskResult.hasAction()) {
                return false;
            }
            this.action = taskResult.action();
            this.printTaskAction = taskResult.actionHandle();
            return true;
        }
//        Action action = guide.getAction(ctx);
        Optional<Action> action = Guides.INSTANCE.buildAction(ctx);
        if (action.isEmpty())
            return false;
        this.action = action.get();
        this.printTaskAction = this.printTasks.createActionHandle(ctx, this.action);
        return true;
    }

    private void updatePrintSkipCache() {
        List<String> skipList = Configs.Print.PRINT_SKIP_LIST.getStrings();
        if (skipList.equals(this.printSkipListCache)) {
            return;
        }
        this.printSkipListCache = new ArrayList<>(skipList);
        this.printSkipFilters = this.printSkipListCache.toArray(new String[0]);
    }

    private boolean shouldSkipRequiredState(BlockState requiredState) {
        if (!Configs.Print.PRINT_SKIP.getBooleanValue() || this.printSkipFilters.length == 0) {
            return false;
        }
        for (String filter : this.printSkipFilters) {
            if (FilterUtils.matchName(filter, requiredState)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        PrintTaskAction taskAction = this.printTaskAction;
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();
            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                if (taskAction != null) {
                    this.printTasks.onActionFailure(taskAction, this.ctx, this.action);
                    skipIteration.set(taskAction.stopIterationAfterAction());
                }
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "下落方块无支撑");
                MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(ctx.requiredBlockName().getString()));
                setIterationConsumedEffectiveExecution(false);
                return;
            }
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) {
            if (taskAction != null) {
                this.printTasks.onActionFailure(taskAction, this.ctx, this.action);
                skipIteration.set(taskAction.stopIterationAfterAction());
            }
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "无有效放置面");
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        if (!InventoryUtils.switchToItems(player, reqItems)) {
            if (taskAction != null) {
                this.printTasks.onActionFailure(taskAction, this.ctx, this.action);
                skipIteration.set(taskAction.stopIterationAfterAction());
            }
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        boolean useShift;
        if (action.getShift() == null) {
            useShift = (Implementation.isInteractive(level.getBlockState(blockPos.relative(side)).getBlock()) && !(action instanceof ClickAction))
                    || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        } else {
            useShift = action.getShift();
        }
        action.queueAction(blockPos, side, useShift, player);
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
        }
        PlayerLook playerLook = action.getPlayerLook();
        if (playerLook != null) {
            Direction primaryLookDirection = DirectionUtils.orderedByNearest(playerLook.getYaw(), playerLook.getPitch())[0];
            if (primaryLookDirection.getAxis().isHorizontal()) {
                float currentPitch = player.getXRot();
                currentPitch = Math.max(-40.0F, Math.min(40.0F, currentPitch));
                playerLook = new PlayerLook(playerLook.getYaw(), currentPitch);
                ActionManager.INSTANCE.setWaitForHorizontalLook(false);
            }
        }
        ActionManager.INSTANCE.setLook(playerLook);
        HudStatsManager.INSTANCE.trackExpectedBlockState(HudStatsManager.Mode.PRINT, blockPos, ctx.requiredState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.PRINT, 1);
        if (!action.isConsumeEffectiveExecution()) {
            setIterationConsumedEffectiveExecution(false);
        }
        boolean needWaitModifyLook = ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook;
        if (!needWaitModifyLook) {
            if (taskAction != null) {
                this.printTasks.onActionSuccess(taskAction, this.ctx, this.action);
            }
        } else if (taskAction != null) {
            this.printTasks.onActionQueued(taskAction, this.ctx, this.action);
        }
        if (needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待转头");
            skipIteration.set(true);
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
        }
        if (taskAction != null && taskAction.stopIterationAfterAction()) {
            skipIteration.set(true);
        }
        int cooldownTicks = action.getCooldownTicksOverride() >= 0
                ? action.getCooldownTicksOverride()
                : ConfigUtils.getPlaceCooldown();
        setBlockPosCooldown(blockPos, cooldownTicks);
    }

    @Override
    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.printTasks.isActiveTaskPos(pos)) {
            return false;
        }
        return super.isBlockPosOnCooldown(pos);
    }

    private void fillSortedTargetQueue(PrinterBox playerInteractionBox, WorldSchematic schematic) {
        int maxTotalIter = getMaxTotalIterationsPerTick();
        int collectLimit = maxTotalIter > 0 ? maxTotalIter : Integer.MAX_VALUE;
        List<BlockPos> positions = new ArrayList<>();
        while (!this.sortedTargetQueue.isEmpty()) {
            positions.add(this.sortedTargetQueue.removeFirst());
        }
        Iterator<BlockPos> iterator = playerInteractionBox.iterator();
        int scanned = 0;
        while (iterator.hasNext() && positions.size() < collectLimit && scanned < collectLimit) {
            BlockPos candidate = iterator.next();
            scanned++;
            if (!schematic.getBlockState(candidate).isAir()) {
                positions.add(candidate);
            }
        }
        positions.sort(createPrintTargetComparator(schematic));
        this.sortedTargetQueue.addAll(positions);
        this.hasMoreSortedIterationPositions = maxTotalIter > 0 && iterator.hasNext();
    }

    private Iterator<BlockPos> createSortedTargetIterator() {
        return new Iterator<>() {
            private boolean returnedSentinel;

            @Override
            public boolean hasNext() {
                return !sortedTargetQueue.isEmpty() || hasMoreSortedIterationPositions && !returnedSentinel;
            }

            @Override
            public BlockPos next() {
                if (!sortedTargetQueue.isEmpty()) {
                    return sortedTargetQueue.removeFirst();
                }
                this.returnedSentinel = true;
                return null;
            }
        };
    }

    private Comparator<BlockPos> createPrintTargetComparator(WorldSchematic schematic) {
        Item heldItem = player.getMainHandItem().getItem();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        return Comparator
                .comparing((BlockPos pos) -> !isHoldingRequiredItem(schematic, heldItem, pos))
                .thenComparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye))
                .thenComparingDouble(pos -> getViewAngleScore(eye, view, pos));
    }

    private static boolean isHoldingRequiredItem(WorldSchematic schematic, Item heldItem, BlockPos pos) {
        return schematic.getBlockState(pos).getBlock().asItem() == heldItem;
    }

    private static double getViewAngleScore(Vec3 eye, Vec3 view, BlockPos pos) {
        Vec3 toTarget = Vec3.atCenterOf(pos).subtract(eye);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        return -view.dot(toTarget.normalize());
    }
}

