package me.aleksilassila.litematica.printer.printer;

import lombok.Setter;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.minecraft.DirectionUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

//#if MC > 12105
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
//#else
//$$ import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
//#endif

@SuppressWarnings("SpellCheckingInspection")
public class ActionManager {
    public static final ActionManager INSTANCE = new ActionManager();

    private QueuedClick queuedClick;
    @Setter
    @Nullable
    public PlayerLook look;
    public boolean needWaitModifyLook = false;
    private boolean waitForHorizontalLook = true;

    private ActionManager() {
    }

    public void queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift) {
        this.queueClick(target, side, hitModifier, useShift, 1);
    }

    public void queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int clickRepeatCount) {
        if (Configs.Placement.PLACE_INTERVAL.getIntegerValue() != 0) {
            if (this.queuedClick != null) {
                System.out.println("Was not ready yet.");
                return;
            }
        }
        this.queuedClick = new QueuedClick(target, side, hitModifier, useShift, clickRepeatCount);
    }

    public void useProtocolHitModifier(@NotNull Vec3 hitModifier) {
        if (this.queuedClick != null) {
            this.queuedClick.useProtocolHit(hitModifier);
        }
    }

    public ActionManager sendQueue(LocalPlayer player) {
        QueuedClick click = this.queuedClick;
        if (click == null) {
            clearQueue();
            return this;
        }
        if (!needWaitModifyLook && look != null) {
            NetworkUtils.sendLookPacket(player, look);
        }
        if (waitForHorizontalLook && !click.useProtocol && !needWaitModifyLook) {
            if (look != null) {
                Direction lookDirection = DirectionUtils.orderedByNearest(look.yaw, look.pitch)[0];
                if (lookDirection.getAxis().isHorizontal()) {
                    needWaitModifyLook = true;
                    return this;
                }
            }
        }
        if (needWaitModifyLook) {
            needWaitModifyLook = false;
        }
        Direction direction;
        if (look == null) {
            direction = click.side;
        } else {
            direction = DirectionUtils.getHorizontalDirection(look.yaw);
        }
        Vec3 hitVec;
        if (!click.useProtocol) {
            Vec3 targetCenter = Vec3.atCenterOf(click.target);
            Vec3 sideOffset = Vec3.atLowerCornerOf(DirectionUtils.getVector(click.side)).scale(0.5);
            Vec3 rotatedHitModifier = click.hitModifier.yRot((direction.toYRot() + 90) % 360).scale(0.5);
            hitVec = targetCenter.add(sideOffset).add(rotatedHitModifier);
        } else {
            hitVec = click.hitModifier;
        }
        if (InventoryUtils.getOrderlyStoreItem() != null) {
            if (InventoryUtils.getOrderlyStoreItem().isEmpty()) {
                SwitchItem.removeItem(InventoryUtils.getOrderlyStoreItem());
            } else {
                SwitchItem.syncUseTime(InventoryUtils.getOrderlyStoreItem());
            }
        }
        boolean wasSneak = player.isShiftKeyDown();
        if (click.useShift && !wasSneak) {
            setShift(player, true);
        } else if (!click.useShift && wasSneak) {
            setShift(player, false);
        }
        MultiPlayerGameModeExtension gameModeExtension = (MultiPlayerGameModeExtension) Reference.MINECRAFT.gameMode;
        if (gameModeExtension != null) {
            boolean localPrediction = !Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
            BlockHitResult blockHitResult = new BlockHitResult(hitVec, click.side, click.target, false);
            for (int i = 0; i < click.repeatCount; i++) {
                gameModeExtension.litematica_printer$useItemOn(localPrediction, InteractionHand.MAIN_HAND, blockHitResult);
            }
        }
        if (click.useShift && !wasSneak) {
            setShift(player, false);
        } else if (!click.useShift && wasSneak) {
            setShift(player, true);
        }
        clearQueue();
        return this;
    }

    public void setShift(LocalPlayer player, boolean shift) {
        //#if MC > 12105
        Input input = new Input(player.input.keyPresses.forward(), player.input.keyPresses.backward(), player.input.keyPresses.left(), player.input.keyPresses.right(), player.input.keyPresses.jump(), shift, player.input.keyPresses.sprint());
        ServerboundPlayerInputPacket packet = new ServerboundPlayerInputPacket(input);
        //#else
        //$$ ServerboundPlayerCommandPacket packet = new ServerboundPlayerCommandPacket(player, shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY);
        //#endif
        player.setShiftKeyDown(shift);
        NetworkUtils.sendPacket(packet);
    }

    public void setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.waitForHorizontalLook = waitForHorizontalLook;
    }

    public void clearQueue() {
        this.queuedClick = null;
        this.needWaitModifyLook = false;
        this.waitForHorizontalLook = true;
        this.look = null;
    }
}
