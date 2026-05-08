package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Blocks;

public final class BedrockBreaker {
    private static final Minecraft CLIENT = Minecraft.getInstance();

    private BedrockBreaker() {
    }

    public static boolean breakBlock(BlockPos pos) {
        return breakBlock(pos, Direction.DOWN, true);
    }

    public static boolean breakBlock(BlockPos pos, boolean predictRemoval) {
        return breakBlock(pos, Direction.DOWN, predictRemoval);
    }

    public static boolean breakBlock(BlockPos pos, Direction direction, boolean predictRemoval) {
        if (CLIENT.level == null || CLIENT.player == null) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("break skipped pos=" + BedrockDebugLog.pos(pos) + " reason=no_level_or_player");
            }
            return false;
        }
        var state = CLIENT.level.getBlockState(pos);
        if (state.isAir()) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("break skipped pos=" + BedrockDebugLog.pos(pos) + " reason=air");
            }
            return false;
        }

        boolean cleanupResidue = BedrockTargetBlocks.isCleanupResidue(state);
        boolean switched = cleanupResidue
                ? BedrockInventory.switchToCleanupTool(state)
                : BedrockInventory.switchToBestTool(state);
        if (!switched) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("break skipped pos=" + BedrockDebugLog.pos(pos) + " reason=missing_effective_tool");
            }
            return false;
        }

        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("break start pos=" + BedrockDebugLog.pos(pos)
                    + " state=" + BedrockDebugLog.describeState(state)
                    + " face=" + direction
                    + " cleanupResidue=" + cleanupResidue
                    + " tool=" + CLIENT.player.getMainHandItem().getItem()
                    + " predictRemoval=" + predictRemoval);
        }

        if (CLIENT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension && !shouldPredictRemoval(state, direction)) {
            gameModeExtension.litematica_printer$continueDestroyBlock(false, pos, direction);
        }

        //#if MC >= 11900
        NetworkUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                pos,
                direction,
                sequence
        ));
        NetworkUtils.sendPacket(sequence -> new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                pos,
                direction,
                sequence
        ));
        //#else
        //$$ NetworkUtils.sendPacket(new ServerboundPlayerActionPacket(
        //$$         ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
        //$$         pos,
        //$$         Direction.DOWN
        //$$ ));
        //$$ NetworkUtils.sendPacket(new ServerboundPlayerActionPacket(
        //$$         ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
        //$$         pos,
        //$$         Direction.DOWN
        //$$ ));
        //#endif

        boolean allowPrediction = predictRemoval && shouldPredictRemoval(state, direction);
        if (predictRemoval && !allowPrediction) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("break prediction suppressed pos=" + BedrockDebugLog.pos(pos)
                        + " reason=" + getPredictionSuppressionReason(state, direction));
            }
        }
        if (allowPrediction) {
            CLIENT.level.removeBlock(pos, false);
        }

        return true;
    }

    private static boolean shouldPredictRemoval(net.minecraft.world.level.block.state.BlockState state, Direction direction) {
        if (CLIENT.getConnection() != null && CLIENT.getSingleplayerServer() == null) {
            return false;
        }

        return !isSingleplayerHorizontalMachineState(state, direction);
    }

    private static String getPredictionSuppressionReason(net.minecraft.world.level.block.state.BlockState state, Direction direction) {
        if (CLIENT.getConnection() != null && CLIENT.getSingleplayerServer() == null) {
            return "server_connection";
        }
        if (isSingleplayerHorizontalMachineState(state, direction)) {
            return "singleplayer_horizontal_machine_state";
        }
        return "prediction_policy";
    }

    private static boolean isSingleplayerHorizontalMachineState(net.minecraft.world.level.block.state.BlockState state, Direction direction) {
        return CLIENT.getSingleplayerServer() != null
                && direction != null
                && direction.getAxis().isHorizontal()
                && (state.is(Blocks.PISTON)
                || state.is(Blocks.MOVING_PISTON)
                || state.is(Blocks.PISTON_HEAD));
    }
}
