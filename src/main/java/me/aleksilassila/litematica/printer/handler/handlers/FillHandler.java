package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class FillHandler extends Module {
    public final static String NAME = "fill";
    private static final int FILL_FRONTIER_CACHE_MIN = 1024;
    private static final int FILL_FRONTIER_CACHE_MAX = 8192;
    private static final int FILL_FRONTIER_CACHE_PER_ACTION = 64;
    private static final Direction[] FILL_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    private List<String> fillCacheBlocklist = new ArrayList<>();
    private List<String> replaceableListCache = List.of();
    private String[] replaceableFilters = new String[0];
    @Getter
    private Item[] fillModeItemList = new Item[0];
    private final ArrayDeque<BlockPos> fillTargets = new ArrayDeque<>();
    private final LongSet fillTargetKeys = new LongOpenHashSet();
    private PrinterBox fillScanBox;
    private int fillScanConfigHash;

    public FillHandler() {
        super(NAME, PrintModeType.FILL, Configs.Core.FILL, Configs.Fill.FILL_SELECTION_TYPE, true);
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
    protected void preprocess() {
        this.updateReplaceableFilterCache();
        FillBlockModeType fillMode = (FillBlockModeType) Configs.Fill.FILL_BLOCK_MODE.getOptionListValue();
        switch (fillMode) {
            case BLOCKLIST:
                // 每次去MC注册表中获取会造成大量卡顿, 所以仅在玩家修改了填充列表, 再去读取以便注册表
                List<String> strings = Configs.Fill.FILL_BLOCK_LIST.getStrings();
                if (!strings.equals(fillCacheBlocklist)) {
                    fillCacheBlocklist = new ArrayList<>(strings);
                    fillModeItemList = new Item[0];
                    if (strings.isEmpty()) {
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "填充列表为空");
                        return;
                    }
                    List<Item> items = RegistryFilterResolver.resolveItems(fillCacheBlocklist);
                    fillModeItemList = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:  // 手持物品
                if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
                    ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
                    if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                        fillModeItemList = new Item[]{player.getMainHandItem().getItem()};
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
                    } else {
                        fillModeItemList = new Item[0];
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "主手无可填充方块");
                    }
                }
                break;
        }
        if (fillModeItemList.length == 0 && fillMode == FillBlockModeType.BLOCKLIST && !fillCacheBlocklist.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "列表无匹配方块");
        }
    }

    private void updateReplaceableFilterCache() {
        List<String> replaceableList = Configs.Print.REPLACEABLE_LIST.getStrings();
        if (replaceableList.equals(this.replaceableListCache)) {
            return;
        }
        this.replaceableListCache = new ArrayList<>(replaceableList);
        this.replaceableFilters = this.replaceableListCache.toArray(new String[0]);
    }

    @Override
    protected boolean canIterate() {
        return fillModeItemList.length > 0;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        this.refillFillTargets(playerInteractionBox);
        int emitLimit = this.getMaxEffectiveExecutionsPerTick();
        return () -> new Iterator<>() {
            private int emitted;

            @Override
            public boolean hasNext() {
                return !fillTargets.isEmpty() && (emitLimit <= 0 || this.emitted < emitLimit);
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                this.emitted++;
                BlockPos result = fillTargets.removeFirst();
                fillTargetKeys.remove(ScanCache.key(result));
                return result;
            }
        };
    }

    private void refillFillTargets(PrinterBox playerInteractionBox) {
        PrinterBox scanSourceBox = this.getScanSourceBox(playerInteractionBox);
        if (scanSourceBox == null) {
            this.clearFillTargets();
            return;
        }
        int configHash = this.getFillScanConfigHash();
        if (this.fillScanBox == null || this.fillScanConfigHash != configHash || !this.fillScanBox.equals(scanSourceBox)) {
            this.resetFillScan(scanSourceBox, configHash);
        }

        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        this.trimInvalidLeadingFillTargets(scanSourceBox, selectionPredicate);

        int targetCacheSize = this.getFillFrontierCacheTarget();
        if (this.fillTargets.size() >= targetCacheSize) {
            return;
        }

        ScanIntent scanIntent = Configs.Print.PLACE_IN_AIR.getBooleanValue() ? ScanIntent.CUSTOM : ScanIntent.FILL;
        Iterable<BlockPos> candidates = ScanCache.INSTANCE.iterable(
                NAME + "_frontier",
                scanSourceBox,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                scanIntent,
                this::isFillTarget,
                pos -> this.isFillCandidatePreFilter(pos, selectionPredicate)
        );
        for (BlockPos blockPos : candidates) {
            if (blockPos == null || this.fillTargets.size() >= targetCacheSize) {
                break;
            }
            this.addFillTargetLast(blockPos);
        }
    }

    private void trimInvalidLeadingFillTargets(PrinterBox playerInteractionBox, Predicate<BlockPos> selectionPredicate) {
        while (!this.fillTargets.isEmpty()) {
            BlockPos queued = this.fillTargets.peekFirst();
            if (this.isFillQueueCandidate(queued, playerInteractionBox, selectionPredicate)) {
                return;
            }
            this.fillTargetKeys.remove(ScanCache.key(queued));
            this.fillTargets.removeFirst();
        }
    }

    private boolean isFillQueueCandidate(BlockPos blockPos, PrinterBox playerInteractionBox, Predicate<BlockPos> selectionPredicate) {
        return playerInteractionBox != null
                && playerInteractionBox.contains(blockPos)
                && this.isFillCandidatePreFilter(blockPos, selectionPredicate)
                && this.isFillTarget(blockPos);
    }

    private boolean isFillCandidatePreFilter(BlockPos blockPos, Predicate<BlockPos> selectionPredicate) {
        return this.canReachIterationPosition(blockPos)
                && selectionPredicate.test(blockPos)
                && !this.isBlockPosOnCooldown(blockPos);
    }

    private void resetFillScan(PrinterBox playerInteractionBox, int configHash) {
        this.fillTargets.clear();
        this.fillTargetKeys.clear();
        this.fillScanBox = this.copyScanBox(playerInteractionBox);
        this.fillScanConfigHash = configHash;
    }

    private void clearFillTargets() {
        this.fillTargets.clear();
        this.fillTargetKeys.clear();
        this.fillScanBox = null;
    }

    private PrinterBox copyScanBox(PrinterBox box) {
        return new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private int getFillFrontierCacheTarget() {
        int placeBudget = this.getMaxEffectiveExecutionsPerTick();
        if (placeBudget <= 0) {
            return FILL_FRONTIER_CACHE_MAX;
        }
        int scaled = placeBudget * FILL_FRONTIER_CACHE_PER_ACTION;
        return Math.max(FILL_FRONTIER_CACHE_MIN, Math.min(FILL_FRONTIER_CACHE_MAX, scaled));
    }

    private int getFillScanConfigHash() {
        int result = Arrays.hashCode(this.fillModeItemList);
        result = 31 * result + Arrays.hashCode(this.replaceableFilters);
        result = 31 * result + Configs.Fill.FILL_BLOCK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Fill.FILL_SELECTION_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.PLACE_IN_AIR.getBooleanValue());
        result = 31 * result + Configs.Fill.FILL_BLOCK_FACING.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue());
        return result;
    }

    private void addFillTargetFirst(BlockPos blockPos) {
        if (blockPos != null && this.fillTargetKeys.add(ScanCache.key(blockPos))) {
            this.fillTargets.addFirst(blockPos.immutable());
        }
    }

    private void addFillTargetLast(BlockPos blockPos) {
        if (blockPos != null && this.fillTargetKeys.add(ScanCache.key(blockPos))) {
            this.fillTargets.addLast(blockPos.immutable());
        }
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
            return !heldStack.isEmpty() && heldStack.getCount() > 0 && this.isFillTarget(blockPos);
        }
        return this.isFillTarget(blockPos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() &&
                player.getMainHandItem().getItem() instanceof BlockItem item &&
                item.getBlock() instanceof FallingBlock block &&
                FallingBlock.isFree(level.getBlockState(blockPos.below()))
        ) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "下落方块无支撑");
            MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            return;
        }
        boolean handheld = Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD;
        BlockState currentState = level.getBlockState(blockPos);
        if (!this.isFillTarget(currentState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!handheld && !InventoryUtils.switchToItems(player, this.fillModeItemList)) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "缺少填充材料");
            return;
        }
        Direction side = this.getFillPlacementSide(blockPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "无有效放置面");
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        Action action = new Action()
                .setLookDirection(side.getOpposite())
                .queueAction(blockPos, side, false, player);
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setWaitForHorizontalLook(false);
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FILL, blockPos, currentState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FILL, 1);
        if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "等待转头");
            skipIteration.set(true);
        } else {
            this.seedAdjacentFillTargets(blockPos);
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
        }
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
    }

    private void seedAdjacentFillTargets(BlockPos blockPos) {
        if (this.fillScanBox == null || blockPos == null) {
            return;
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = blockPos.relative(direction);
            if (this.isFillQueueCandidate(neighbor, this.fillScanBox, selectionPredicate)) {
                this.addFillTargetFirst(neighbor);
            }
        }
    }

    private Direction getFillPlacementSide(BlockPos blockPos) {
        if (this.level == null || this.player == null || blockPos == null) {
            return null;
        }
        Direction configuredFacing = ConfigUtils.getFillModeFacing();
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            return configuredFacing != null ? configuredFacing : getPlayerPlacementDirection();
        }
        if (configuredFacing != null) {
            return this.isValidFillPlacementSide(blockPos, configuredFacing) ? configuredFacing : null;
        }
        for (Direction side : FILL_SIDE_ORDER) {
            if (this.isValidFillPlacementSide(blockPos, side)) {
                return side;
            }
        }
        return null;
    }

    private boolean isValidFillPlacementSide(BlockPos blockPos, Direction side) {
        BlockPos neighborPos = blockPos.relative(side);
        BlockState neighborState = this.level.getBlockState(neighborPos);
        return PrinterUtils.canBeClicked(this.level, neighborPos) && !BlockUtils.isReplaceable(neighborState);
    }

    private boolean isFillTarget(BlockPos blockPos) {
        return this.level != null && this.isFillTarget(this.level.getBlockState(blockPos));
    }

    private boolean isFillTarget(BlockState currentState) {
        if (currentState.isAir() || currentState.getBlock() instanceof LiquidBlock) {
            return true;
        }
        for (String filter : this.replaceableFilters) {
            if (FilterUtils.matchName(filter, currentState)) {
                return true;
            }
        }
        return false;
    }

}
