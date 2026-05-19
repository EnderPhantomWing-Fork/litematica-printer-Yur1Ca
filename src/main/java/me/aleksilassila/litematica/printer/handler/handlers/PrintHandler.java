package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.render.WorkTargetHighlighter;
import me.aleksilassila.litematica.printer.utils.*;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class PrintHandler extends ClientPlayerTickHandler {
    public final static String NAME = "print";

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;

    private SchematicBlockContext ctx;

    private final Deque<BlockPos> sortedTargetQueue = new ArrayDeque<>();
    private PrinterBox sortedTargetBox;
    private boolean hasMoreSortedIterationPositions;

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
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        if (!Configs.Print.PRINT_SORT_TARGETS.getBooleanValue()) {
            this.sortedTargetQueue.clear();
            this.sortedTargetBox = null;
            return playerInteractionBox;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
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
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            Set<String> skipSet = new HashSet<>(Configs.Print.PRINT_SKIP_LIST.getStrings()); // 转换为 HashSet
            if (skipSet.stream().anyMatch(s -> FilterUtils.matchName(s, ctx.requiredState))) {
                return false;
            }
        }
//        Action action = guide.getAction(ctx);
        Optional<Action> action = Guides.INSTANCE.buildAction(ctx);
        if (action.isEmpty())
            return false;
        this.action = action.get();
        return true;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();
            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "下落方块无支撑");
                MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(ctx.requiredBlockName().getString()));
                return;
            }
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "无有效放置面");
            return;
        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        if (!InventoryUtils.switchToItems(player, reqItems)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "缺少材料");
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
        WorkTargetHighlighter.record(HudStatsManager.Mode.PRINT, blockPos);
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
        if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.PRINT, "等待转头");
            skipIteration.set(true);
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.PRINT, "运行中");
        }
        setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private void fillSortedTargetQueue(PrinterBox playerInteractionBox, WorldSchematic schematic) {
        int maxTotalIter = getMaxTotalIterationsPerTick();
        int collectLimit = maxTotalIter > 0 ? Math.max(0, maxTotalIter - 1) : Integer.MAX_VALUE;
        List<BlockPos> positions = new ArrayList<>();
        while (!this.sortedTargetQueue.isEmpty()) {
            positions.add(this.sortedTargetQueue.removeFirst());
        }
        Iterator<BlockPos> iterator = playerInteractionBox.iterator();
        while (iterator.hasNext() && positions.size() < collectLimit) {
            positions.add(iterator.next());
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

