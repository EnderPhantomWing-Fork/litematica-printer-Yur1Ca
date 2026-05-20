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
        float currentProgress = this.getDestroyProgress(player, state, player.getMainHandItem());
        float bestProgress = this.getBestDestroyProgress(player, state, currentProgress);
        if (bestProgress <= 0.0F && !player.getAbilities().instabuild) {
            return null;
        }
        return new Target(pos.immutable(), state, currentProgress, bestProgress, Direction.DOWN);
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

    private float getBestDestroyProgress(LocalPlayer player, BlockState state, float currentProgress) {
        float bestProgress = currentProgress;
        if (!this.shouldResolveBestTool()) {
            return bestProgress;
        }
        for (ItemStack stack : InventoryUtils.getMainStacks(player.getInventory())) {
            if (stack.isEmpty()) {
                continue;
            }
            float progress = this.getDestroyProgress(player, state, stack);
            if (progress > bestProgress) {
                bestProgress = progress;
            }
        }
        return bestProgress;
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

        private Target(BlockPos pos, BlockState state, float currentProgress, float bestProgress, Direction direction) {
            this.pos = pos;
            this.state = state;
            this.currentProgress = currentProgress;
            this.bestProgress = bestProgress;
            this.direction = direction;
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
    }
}
