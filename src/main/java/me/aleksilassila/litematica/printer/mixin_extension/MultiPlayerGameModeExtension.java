package me.aleksilassila.litematica.printer.mixin_extension;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

@SuppressWarnings("UnusedReturnValue")
public interface MultiPlayerGameModeExtension {
    InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit);

    default BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction) {
        return this.litematica_printer$continueDestroyBlock(localPrediction, blockPos, direction, false);
    }

    BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction, boolean forceDelayedDestroy);

    default BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos blockPos, Direction direction) {
        return this.litematica_printer$continueDestroyBlock(false, blockPos, direction, true);
    }

    default boolean litematica_printer$isPendingDelayedDestroy(BlockPos blockPos) {
        return false;
    }
}
