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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * 水源/含水方块处理指南。
 */
public class WaterGuide extends Guide {
    private static final int WATER_SYNC_WAIT_TICKS = 5;
    private static final int WATER_WORKFLOW_TIMEOUT_TICKS = 20;
    private static final Map<BlockPos, Long> RECENTLY_BROKEN_ICE = new HashMap<>();
    @Nullable
    private static BlockPos activeWorkflowPos;
    private static long activeWorkflowTick = -1L;
    private static boolean activeWorkflowReadyForPlacement;

    public WaterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return BlockStateUtils.isWaterBlock(requiredState);
    }

    @Override
    protected Result onBuildAction(BlockMatchResult state) {
        cleanupExpiredBreaks();
        cleanupExpiredWorkflow();
        if (client.gameMode == null || client.gameMode.getPlayerMode().isCreative()) {
            clearWorkflowIfCurrent();
            return Result.SKIP;
        }
        if (shouldSkipWaterloggedTarget()) {
            clearWorkflowIfCurrent();
            return Result.SKIP;
        }
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            clearWorkflowIfCurrent();
            return Result.SKIP;
        }
        if (currentBlock instanceof IceBlock) {
            activateWorkflow(blockPos);
            if (isWaitingForWaterSync()) {
                return Result.SKIP;
            }
            if (!canIceBecomeWaterSource()) {
                clearWorkflowIfCurrent();
                return Result.SKIP;
            }
            switchToNonSilkTouchPickaxe();
            InteractionUtils.INSTANCE.add(context);
            markWaitingForWaterSync();
            return Result.SKIP;
        }
        if (BlockStateUtils.isCorrectWaterLevel(requiredState, currentState)) {
            clearWaitingForWaterSync();
            if (isWaterloggedTarget()) {
                markWorkflowReadyForPlacement();
            } else {
                clearWorkflowIfCurrent();
            }
            return Result.PASS;
        }
        if (isWaitingForWaterSync()) {
            activateWorkflow(blockPos);
            return Result.SKIP;
        }
        if (isWaterloggedTarget() && !BlockStateUtils.isReplaceable(currentState)) {
            clearWorkflowIfCurrent();
            return Result.PASS;
        }
        if (!canIceBecomeWaterSource()) {
            clearWorkflowIfCurrent();
            return Result.SKIP;
        }
        return Result.success(new Action().setItem(Items.ICE).setSides(net.minecraft.core.Direction.DOWN));
    }

    @Override
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        if (!isActiveWorkflowPos(blockPos)) {
            return Result.PASS;
        }
        if (!isWaterloggedTarget()) {
            clearWorkflowIfCurrent();
            return Result.PASS;
        }
        boolean currentWaterlogged = currentState.hasProperty(BlockStateProperties.WATERLOGGED)
                && currentState.getValue(BlockStateProperties.WATERLOGGED);
        if (currentBlock == requiredBlock && !currentWaterlogged) {
            if (InteractionUtils.canBreakBlock(blockPos)) {
                InteractionUtils.INSTANCE.add(context);
                return Result.SKIP;
            }
            clearWorkflowIfCurrent();
            return Result.SKIP;
        }
        clearWorkflowIfCurrent();
        return Result.PASS;
    }

    public static @Nullable BlockPos getActiveWorkflowPos() {
        cleanupExpiredWorkflow();
        return activeWorkflowPos;
    }

    public static boolean isActiveWorkflowPos(@Nullable BlockPos pos) {
        cleanupExpiredWorkflow();
        return pos != null && pos.equals(activeWorkflowPos);
    }

    public static boolean isWorkflowReadyForPlacement(@Nullable BlockPos pos) {
        cleanupExpiredWorkflow();
        return activeWorkflowReadyForPlacement && pos != null && pos.equals(activeWorkflowPos);
    }

    public static void activateWorkflow(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos immutablePos = pos.immutable();
        if (!immutablePos.equals(activeWorkflowPos)) {
            activeWorkflowPos = immutablePos;
            activeWorkflowTick = ClientPlayerTickManager.getCurrentHandlerTime();
        }
        activeWorkflowReadyForPlacement = false;
    }

    public static void completeWorkflow(@Nullable BlockPos pos) {
        if (pos != null && pos.equals(activeWorkflowPos)) {
            clearWorkflow();
        }
    }

    public static boolean isWorkflowPlacementAction(SchematicBlockContext context, Action action) {
        if (context == null || action == null || !BlockStateUtils.isWaterBlock(context.requiredState)) {
            return false;
        }
        for (Item item : action.getRequiredItems(context.requiredState.getBlock())) {
            if (item == Items.ICE) {
                return true;
            }
        }
        return false;
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

    private boolean isWaterloggedTarget() {
        return requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                && requiredState.getValue(BlockStateProperties.WATERLOGGED);
    }

    private void markWaitingForWaterSync() {
        activateWorkflow(blockPos);
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

    private void markWorkflowReadyForPlacement() {
        activateWorkflow(blockPos);
        activeWorkflowReadyForPlacement = true;
    }

    private void clearWorkflowIfCurrent() {
        if (blockPos.equals(activeWorkflowPos)) {
            clearWorkflow();
        }
    }

    private static void cleanupExpiredWorkflow() {
        if (activeWorkflowPos == null) {
            return;
        }
        long currentTick = ClientPlayerTickManager.getCurrentHandlerTime();
        if (activeWorkflowTick >= 0L && currentTick - activeWorkflowTick >= WATER_WORKFLOW_TIMEOUT_TICKS) {
            clearWorkflow();
        }
    }

    private static void clearWorkflow() {
        activeWorkflowPos = null;
        activeWorkflowTick = -1L;
        activeWorkflowReadyForPlacement = false;
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
