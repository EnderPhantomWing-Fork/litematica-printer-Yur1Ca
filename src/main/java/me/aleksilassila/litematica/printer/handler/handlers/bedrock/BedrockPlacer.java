package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BedrockPlacer {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static float lastYaw;
    private static float lastPitch;

    private BedrockPlacer() {
    }

    public static boolean placeSimple(BlockPos supportPos, Direction clickedFace, Item item) {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("placeSimple skipped support=" + BedrockDebugLog.pos(supportPos) + " item=" + item + " reason=no_player_or_gamemode");
            }
            return false;
        }
        if (!BedrockInventory.switchToOffhand(item)) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("placeSimple skipped support=" + BedrockDebugLog.pos(supportPos) + " item=" + item + " reason=missing_item");
            }
            return false;
        }
        PlayerLook look = new PlayerLook(clickedFace.getOpposite());
        rememberLook(player);
        sendLookPacketBypassingActionManager(player, look);
        syncLocalLook(player, look.getYaw(), look.getPitch());
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        placeBlockAggressively(player, hitResult, true);
        restoreLook(player);
        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("placeSimple support=" + BedrockDebugLog.pos(supportPos)
                    + " face=" + clickedFace
                    + " item=" + item
                    + " hitPos=" + hitResult.getBlockPos());
        }
        return true;
    }

    public static boolean placePiston(BlockPos pistonPos, Direction facing) {
        return placePiston(pistonPos, facing, pistonPos.relative(facing.getOpposite()));
    }

    public static boolean placePiston(BlockPos pistonPos, Direction facing, BlockPos... preferredAnchors) {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("placePiston skipped piston=" + BedrockDebugLog.pos(pistonPos) + " facing=" + facing + " reason=no_player_or_gamemode");
            }
            return false;
        }
        if (!BedrockInventory.switchToOffhand(Blocks.PISTON.asItem())) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("placePiston skipped piston=" + BedrockDebugLog.pos(pistonPos) + " facing=" + facing + " reason=missing_piston");
            }
            return false;
        }

        // Pistons face opposite to the direction the player is looking when placed.
        // We want the resulting piston facing to match `facing`, so look at the opposite side.
        PlayerLook look = new PlayerLook(facing.getOpposite());
        // Packet-mode piston placement only needs the server-side look. Touching the
        // local player rotation here can produce an immediate vanilla look update,
        // which races horizontal piston placement in some singleplayer environments.
        sendLookPacketBypassingActionManager(player, look);

        BlockPos clickedPos = pistonPos.relative(facing.getOpposite());
        Direction clickedFace = facing;
        if (CLIENT.level != null) {
            BlockPos[] anchors = preferredAnchors != null && preferredAnchors.length > 0
                    ? preferredAnchors
                    : new BlockPos[]{clickedPos};
            BedrockEnvironment.PlacementInteraction placementInteraction =
                    BedrockEnvironment.findPlacementInteraction(CLIENT.level, pistonPos, anchors);
            if (placementInteraction != null) {
                clickedPos = placementInteraction.anchorPos();
                clickedFace = placementInteraction.clickedFace();
            }
        }

        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(clickedPos), clickedFace, clickedPos, false);

        placeBlockAggressively(player, hitResult, false);
        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("placePiston piston=" + BedrockDebugLog.pos(pistonPos)
                    + " facing=" + facing
                    + " clickedFace=" + clickedFace
                    + " clickedBlock=" + BedrockDebugLog.pos(clickedPos)
                    + " sentYaw=" + look.getYaw()
                    + " sentPitch=" + look.getPitch());
        }
        return true;
    }

    private static void placeBlockAggressively(LocalPlayer player, BlockHitResult hitResult, boolean allowLocalUseFallback) {
        boolean useShift = CLIENT.level != null && BedrockTargetBlocks.requiresSneakPlacement(CLIENT.level.getBlockState(hitResult.getBlockPos()));
        boolean wasSneak = player.isShiftKeyDown();
        if (useShift && !wasSneak) {
            ActionManager.INSTANCE.setShift(player, true);
        }
        try {
            InteractionUtils.INSTANCE.useItemOn(false, InteractionHand.OFF_HAND, hitResult);
            if (allowLocalUseFallback) {
                ItemStack offhand = player.getOffhandItem();
                if (!offhand.isEmpty()) {
                    offhand.useOn(new UseOnContext(player, InteractionHand.OFF_HAND, hitResult));
                }
            }
        } finally {
            if (useShift && !wasSneak) {
                ActionManager.INSTANCE.setShift(player, false);
            }
        }
    }

    private static void sendLookPacketBypassingActionManager(LocalPlayer player, PlayerLook look) {
        PlayerLook queuedLook = ActionManager.INSTANCE.look;
        try {
            ActionManager.INSTANCE.look = null;
            NetworkUtils.sendLookPacket(player, look);
        } finally {
            ActionManager.INSTANCE.look = queuedLook;
        }
    }

    private static void rememberLook(LocalPlayer player) {
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
    }

    private static void syncLocalLook(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    private static void restoreLook(LocalPlayer player) {
        syncLocalLook(player, lastYaw, lastPitch);
        // Keep the server on the placement look until its use-item packet has been
        // processed; restoring the server-side look immediately can race horizontal
        // piston placement and make the piston pick the player's real camera yaw.
    }
}
