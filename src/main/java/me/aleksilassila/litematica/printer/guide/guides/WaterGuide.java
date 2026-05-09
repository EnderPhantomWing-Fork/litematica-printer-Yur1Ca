package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockStateUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * 水源/含水方块处理指南。
 */
public class WaterGuide extends Guide {
    private static final int WATER_SYNC_WAIT_TICKS = 10;
    private static final Map<BlockPos, Long> RECENTLY_BROKEN_ICE = new HashMap<>();

    public WaterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return requiredState.is(Blocks.WATER) || requiredBlock instanceof BubbleColumnBlock;
    }

    @Override
    protected Result onBuildAction(BlockMatchResult state) {
        cleanupExpiredBreaks();
        if (client.gameMode == null || client.gameMode.getPlayerMode().isCreative()) {
            return Result.SKIP;
        }
        if (shouldSkipWaterloggedTarget()) {
            return Result.SKIP;
        }
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return Result.SKIP;
        }
        if (currentBlock instanceof IceBlock) {
            if (isWaitingForWaterSync()) {
                return Result.SKIP;
            }
            if (!canIceBecomeWaterSource()) {
                return Result.SKIP;
            }
            switchToNonSilkTouchPickaxe();
            InteractionUtils.INSTANCE.add(context);
            markWaitingForWaterSync();
            return Result.SKIP;
        }
        if (BlockStateUtils.isCorrectWaterLevel(requiredState, currentState)) {
            clearWaitingForWaterSync();
            return Result.PASS;
        }
        if (isWaitingForWaterSync()) {
            return Result.SKIP;
        }
        if (!canIceBecomeWaterSource()) {
            return Result.SKIP;
        }
        return Result.success(new Action().setItem(Items.ICE).setSides(net.minecraft.core.Direction.DOWN));
    }

    /**
     * 仅当破冰后能实际变成水时，才允许破冰/放冰。
     * 原版要求下方为阻挡运动的方块，或下方本身含有流体。
     */
    private boolean canIceBecomeWaterSource() {
        BlockPos belowPos = blockPos.below();
        BlockState belowState = level.getBlockState(belowPos);
        //#if MC > 11904
        return !belowState.getCollisionShape(level, belowPos, CollisionContext.empty()).isEmpty()
                || !belowState.getFluidState().isEmpty();
        //#else
        //$$ return belowState.getMaterial().blocksMotion() || belowState.getMaterial().isLiquid();
        //#endif
    }

    private boolean shouldSkipWaterloggedTarget() {
        return Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue()
                && requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                && requiredState.getValue(BlockStateProperties.WATERLOGGED);
    }

    private void markWaitingForWaterSync() {
        RECENTLY_BROKEN_ICE.put(blockPos.immutable(), ClientPlayerTickManager.getCurrentHandlerTime());
    }

    private void clearWaitingForWaterSync() {
        RECENTLY_BROKEN_ICE.remove(blockPos);
    }

    private boolean isWaitingForWaterSync() {
        Long tick = RECENTLY_BROKEN_ICE.get(blockPos);
        return tick != null && ClientPlayerTickManager.getCurrentHandlerTime() - tick < WATER_SYNC_WAIT_TICKS;
    }

    private void cleanupExpiredBreaks() {
        long currentTick = ClientPlayerTickManager.getCurrentHandlerTime();
        Iterator<Map.Entry<BlockPos, Long>> iterator = RECENTLY_BROKEN_ICE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            if (currentTick - entry.getValue() >= WATER_SYNC_WAIT_TICKS) {
                iterator.remove();
            }
        }
    }

    private void switchToNonSilkTouchPickaxe() {
        if (client.player == null) {
            return;
        }
        if (isNonSilkTouchPickaxe(client.player.getMainHandItem())) {
            return;
        }
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isNonSilkTouchPickaxe(stack)) {
                InventoryUtils.setPickedItemToHand(slot, stack, client);
                return;
            }
        }
    }

    private boolean isNonSilkTouchPickaxe(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String itemPath = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!itemPath.endsWith("_pickaxe")) {
            return false;
        }
        //#if MC > 12006
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            Optional<ResourceKey<Enchantment>> enchantmentKey = enchantment.unwrapKey();
            if (enchantmentKey.isPresent() && enchantmentKey.get() == Enchantments.SILK_TOUCH) {
                return false;
            }
        }
        //#else
        //$$ if (EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0) {
        //$$     return false;
        //$$ }
        //#endif
        return true;
    }
}
