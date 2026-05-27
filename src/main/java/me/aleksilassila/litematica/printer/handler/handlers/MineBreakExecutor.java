package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

final class MineBreakExecutor {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final float FAST_FINISH_PROGRESS = 0.5F;
    private static final float CURRENT_TOOL_MIN_EFFICIENCY_RATIO = 0.75F;

    public void reset() {
    }

    @Nullable
    public Target analyze(BlockPos pos) {
        LocalPlayer player = CLIENT.player;
        ClientLevel level = CLIENT.level;
        if (player == null || level == null || pos == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!InteractionUtils.canBreakBlock(pos)) {
            return null;
        }
        ItemStack currentStack = player.getMainHandItem();
        float currentProgress = this.getDestroyProgress(player, state, player.getMainHandItem());
        ToolChoice toolChoice = this.getBestToolChoice(player, state, currentStack, currentProgress);
        float bestProgress = toolChoice.progress();
        if (bestProgress <= 0.0F && !player.getAbilities().instabuild) {
            return null;
        }
        return new Target(pos.immutable(), state, currentProgress, bestProgress, Direction.DOWN, toolChoice.item());
    }

    public boolean isInstantWithCurrentTool(Target target) {
        LocalPlayer player = CLIENT.player;
        return player != null && (player.getAbilities().instabuild || target.currentProgress > FAST_FINISH_PROGRESS);
    }

    public boolean isInstantWithBestTool(Target target) {
        LocalPlayer player = CLIENT.player;
        return player != null && (player.getAbilities().instabuild || target.bestProgress > FAST_FINISH_PROGRESS);
    }

    public boolean canUseCurrentTool(Target target) {
        return target.currentProgress > 0.0F && this.isCurrentToolEfficient(target);
    }

    public boolean canUseBetterTool(Target target) {
        return target.bestProgress > target.currentProgress;
    }

    public boolean isCurrentToolEffective(Target target) {
        LocalPlayer player = CLIENT.player;
        if (player == null || !this.shouldResolveBestTool()) {
            return true;
        }
        if (target.bestToolItem == player.getMainHandItem().getItem()) {
            return true;
        }
        return target.currentProgress >= target.bestProgress * CURRENT_TOOL_MIN_EFFICIENCY_RATIO;
    }

    public boolean hasSameBestTool(Target target, @Nullable Item item) {
        return target != null && target.bestToolItem == item;
    }

    private ToolChoice getBestToolChoice(LocalPlayer player, BlockState state, ItemStack currentStack, float currentProgress) {
        float bestProgress = currentProgress;
        Item bestItem = currentStack.getItem();
        if (!this.shouldResolveBestTool()) {
            return new ToolChoice(bestItem, bestProgress);
        }
        for (ItemStack stack : InventoryUtils.getMainStacks(player.getInventory())) {
            if (stack.isEmpty()) {
                continue;
            }
            float progress = this.getDestroyProgress(player, state, stack);
            if (progress > bestProgress) {
                bestProgress = progress;
                bestItem = stack.getItem();
            }
        }
        return new ToolChoice(bestItem, bestProgress);
    }

    private boolean shouldResolveBestTool() {
        return Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()
                || ModLoadUtils.isTweakerooLoaded() && TweakerooUtils.isToolSwitchEnabled();
    }

    private boolean isCurrentToolEfficient(Target target) {
        if (!this.shouldResolveBestTool()) {
            return true;
        }
        if (target.bestProgress <= 0.0F) {
            return false;
        }
        return target.currentProgress >= target.bestProgress * CURRENT_TOOL_MIN_EFFICIENCY_RATIO;
    }

    private float getDestroyProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        if (!InteractionUtils.isToolAllowedByDurabilityProtection(stack)) {
            return 0.0F;
        }
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }

    public static final class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final float currentProgress;
        private final float bestProgress;
        private final Direction direction;
        private final Item bestToolItem;

        private Target(BlockPos pos, BlockState state, float currentProgress, float bestProgress, Direction direction, Item bestToolItem) {
            this.pos = pos;
            this.state = state;
            this.currentProgress = currentProgress;
            this.bestProgress = bestProgress;
            this.direction = direction;
            this.bestToolItem = bestToolItem;
        }

        public BlockPos pos() {
            return this.pos;
        }

        public float progress() {
            return this.bestProgress;
        }

        public float currentProgress() {
            return this.currentProgress;
        }

        public Item bestToolItem() {
            return this.bestToolItem;
        }
    }

    private record ToolChoice(Item item, float progress) {
    }
}
