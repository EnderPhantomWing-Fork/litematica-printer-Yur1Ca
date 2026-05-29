package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class FillHandler extends Module {
    public final static String NAME = "fill";

    private List<String> fillCacheBlocklist = new ArrayList<>();
    private List<String> replaceableListCache = List.of();
    private String[] replaceableFilters = new String[0];
    @Getter
    private Item[] fillModeItemList = new Item[0];

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
        return this.getFilteredIterationPositions(playerInteractionBox, this::canIterationBlockPos);
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
        Action action;
        if (ConfigUtils.getFillModeFacing() != null) {
            action = new Action()
                    .setLookDirection(ConfigUtils.getFillModeFacing().getOpposite())
                    .queueAction(blockPos, ConfigUtils.getFillModeFacing(), false, player);
        } else {
            action = new Action()
                    .queueAction(blockPos, getPlayerPlacementDirection(), false, player);
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.FILL, blockPos, currentState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.FILL, 1);
        if (ActionManager.INSTANCE.sendQueue(player).needWaitModifyLook){
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.FILL, "等待转头");
            skipIteration.set(true);
        } else {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.FILL, "运行中");
        }
        this.setBlockPosCooldown(blockPos, ConfigUtils.getPlaceCooldown());
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
