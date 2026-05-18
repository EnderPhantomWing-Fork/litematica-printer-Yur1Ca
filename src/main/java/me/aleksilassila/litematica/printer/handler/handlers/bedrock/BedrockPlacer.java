package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
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

import java.util.HashMap;
import java.util.Map;

public final class BedrockPlacer {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final long HORIZONTAL_LOOK_SETTLE_TICKS = 1L;
    private static final Map<BlockPos, PendingHorizontalPlacement> pendingHorizontalPistonPlacements = new HashMap<>();

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
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
        // Use center of the support block for more reliable interaction
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(supportPos), clickedFace, supportPos, false);
        placeBlockAggressively(player, hitResult, true);
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

    public static boolean preparePistonPlacementLook(BlockPos pistonPos, Direction facing) {
        LocalPlayer player = CLIENT.player;
        if (player == null || CLIENT.gameMode == null) {
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("preparePistonLook skipped piston=" + BedrockDebugLog.pos(pistonPos)
                        + " facing=" + facing
                        + " reason=no_player_or_gamemode");
            }
            return false;
        }

        PlayerLook look = new PlayerLook(facing.getOpposite());
        return !ensureHorizontalLookSettled(player, pistonPos, facing, look, false);
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
        if (ensureHorizontalLookSettled(player, pistonPos, facing, look, true)) {
            return false;
        }
        applyPlacementLook(player, look);

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

    private static boolean ensureHorizontalLookSettled(LocalPlayer player, BlockPos pistonPos, Direction facing, PlayerLook look, boolean consumeReadyPlacement) {
        Direction lookDirection = DirectionUtils.orderedByNearest(look.getYaw(), look.getPitch())[0];
        BlockPos pendingKey = pistonPos.immutable();
        if (!lookDirection.getAxis().isHorizontal()) {
            pendingHorizontalPistonPlacements.remove(pendingKey);
            return false;
        }

        PendingHorizontalPlacement pendingPlacement = pendingHorizontalPistonPlacements.get(pendingKey);
        if (pendingPlacement != null && facing == pendingPlacement.facing()) {
            if (!isHorizontalLookReady(pendingPlacement)) {
                return true;
            }
            if (consumeReadyPlacement) {
                pendingHorizontalPistonPlacements.remove(pendingKey);
            }
            return false;
        }

        long sentTick = ClientPlayerTickManager.getCurrentHandlerTime();
        pendingHorizontalPistonPlacements.put(pendingKey, new PendingHorizontalPlacement(facing, look, sentTick, -1L));
        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("preparePistonLook deferred piston=" + BedrockDebugLog.pos(pistonPos)
                    + " facing=" + facing
                    + " reason=horizontal_look_settle"
                    + " sentTick=" + sentTick);
        }
        return true;
    }

    private static boolean isHorizontalLookReady(PendingHorizontalPlacement pendingPlacement) {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (pendingPlacement.tailSentTick() < 0L) {
            return false;
        }
        return now - pendingPlacement.tailSentTick() >= HORIZONTAL_LOOK_SETTLE_TICKS;
    }

    public static void sendPendingHorizontalLookAtTickTail(LocalPlayer player) {
        if (player == null || pendingHorizontalPistonPlacements.isEmpty()) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        PendingHorizontalPlacement selectedPlacement = pendingHorizontalPistonPlacements.values().iterator().next();
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, selectedPlacement.look());
        for (Map.Entry<BlockPos, PendingHorizontalPlacement> entry : pendingHorizontalPistonPlacements.entrySet()) {
            PendingHorizontalPlacement pendingPlacement = entry.getValue();
            if (pendingPlacement.facing() != selectedPlacement.facing()) {
                continue;
            }
            entry.setValue(pendingPlacement.withTailSentTick(now));
            if (BedrockDebugLog.isEnabled()) {
                BedrockDebugLog.write("preparePistonLook tail sent piston=" + BedrockDebugLog.pos(entry.getKey())
                        + " facing=" + pendingPlacement.facing()
                        + " tick=" + now);
            }
        }
    }

    private static void applyPlacementLook(LocalPlayer player, PlayerLook look) {
        NetworkUtils.sendLookPacketIgnoringQueuedLook(player, look);
    }

    private record PendingHorizontalPlacement(Direction facing, PlayerLook look, long sentTick, long tailSentTick) {
        private PendingHorizontalPlacement withTailSentTick(long tailSentTick) {
            return new PendingHorizontalPlacement(this.facing, this.look, this.sentTick, tailSentTick);
        }
    }
}
