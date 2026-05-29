package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineDebugLog;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem;

final class TickScheduler {
    private static final Minecraft MC = Minecraft.getInstance();

    private final ImmutableList<Module> modules;
    private int packetTick;
    private int packetEpoch;
    private String lastPauseReason;

    TickScheduler(ImmutableList<Module> modules) {
        this.modules = modules;
    }

    void tick() {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            HudStatsManager.INSTANCE.resetAll();
            this.lastPauseReason = null;
        }
        if (this.pauseForInventoryState("shared_precheck")) {
            return;
        }
        if (this.pauseForPendingLookQueue()) {
            ActionManager.INSTANCE.sendQueue(MC.player);
            return;
        }
        if (this.pauseForLagCheck()) {
            return;
        }
        TickContext context = TickContext.capture();
        this.resume();
        for (Module handler : this.modules) {
            if (!(handler instanceof GuiHandler)) {
                if (this.pauseForHandlerPrecheck(handler)) {
                    return;
                }
            }
            handler.tick(context);
        }
    }

    int getPacketTick() {
        return this.packetTick;
    }

    void setPacketTick(int packetTick) {
        this.packetTick = packetTick;
    }

    int getPacketEpoch() {
        return this.packetEpoch;
    }

    void recordInboundPacket() {
        this.packetTick = 0;
        this.packetEpoch++;
    }

    String getLastPauseReason() {
        return this.lastPauseReason;
    }

    private void pause(String reason) {
        if (!reason.equals(this.lastPauseReason)) {
            MineDebugLog.write("scheduler pause reason=" + reason + " packetTick=" + this.packetTick);
            this.lastPauseReason = reason;
        }
    }

    private void resume() {
        if (this.lastPauseReason != null) {
            MineDebugLog.write("scheduler resume after=" + this.lastPauseReason + " packetTick=" + this.packetTick);
            this.lastPauseReason = null;
        }
    }

    private boolean pauseForInventoryState(String reasonPrefix) {
        boolean openHandler = isOpenHandler;
        boolean switchingItem = switchItem();
        if (openHandler || switchingItem) {
            this.pause(reasonPrefix + " openHandler=" + openHandler + " switchingItem=" + switchingItem);
            return true;
        }
        return false;
    }

    private boolean pauseForPendingLookQueue() {
        if (!ActionManager.INSTANCE.needWaitModifyLook) {
            return false;
        }
        this.pause("send_queue_wait_modify_look");
        return true;
    }

    private boolean pauseForLagCheck() {
        if (!Configs.Core.LAG_CHECK.getBooleanValue()) {
            return false;
        }
        if (this.packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            this.pause("lag_check packetTick=" + this.packetTick + " max=" + Configs.Core.LAG_CHECK_MAX.getIntegerValue());
            return true;
        }
        this.packetTick++;
        return false;
    }

    private boolean pauseForHandlerPrecheck(Module handler) {
        if (this.pauseForInventoryState("handler_precheck handler=" + handler.getId())) {
            return true;
        }
        if (ActionManager.INSTANCE.needWaitModifyLook) {
            this.pause("action_wait_modify_look handler=" + handler.getId());
            return true;
        }
        return false;
    }
}
