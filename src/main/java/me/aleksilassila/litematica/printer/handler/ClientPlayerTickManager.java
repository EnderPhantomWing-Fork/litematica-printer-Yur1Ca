package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem;

@SuppressWarnings("SpellCheckingInspection")
public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GuiHandler GUI = Modules.GUI;
    public static final PrintHandler PRINT = Modules.PRINT;
    public static final FillHandler FILL = Modules.FILL;
    public static final MineHandler MINE = Modules.MINE;
    public static final FluidHandler FLUID = Modules.FLUID;
    public static final BedrockHandler BEDROCK = Modules.BEDROCK;

    @Getter
    @Setter
    private static int packetTick;
    @Getter
    private static int packetEpoch;
    private static String lastPauseReason;

    public static final ImmutableList<Module> VALUES = Modules.VALUES;

    public static void tick() {
        if (!Configs.Core.WORK_SWITCH.getBooleanValue()) {
            HudStatsManager.INSTANCE.resetAll();
            lastPauseReason = null;
        }
        if (pauseForInventoryState("shared_precheck")) {
            return;
        }
        if (pauseForPendingLookQueue()) {
            ActionManager.INSTANCE.sendQueue(mc.player);
            return;
        }
        if (pauseForLagCheck()) {
            return;
        }
        TickContext context = TickContext.capture();
        resume();
        for (Module handler : VALUES) {
            if (!(handler instanceof GuiHandler)) {
                if (pauseForHandlerPrecheck(handler)) {
                    return;
                }
            }
            handler.tick(context);
        }
    }

    public static long getCurrentHandlerTime() {
        return TickContext.currentGameTime();
    }

    public static void recordInboundPacket() {
        packetTick = 0;
        packetEpoch++;
    }

    public static String getLastPauseReason() {
        return lastPauseReason;
    }

    private static void pause(String reason) {
        if (!reason.equals(lastPauseReason)) {
            MineDebugLog.write("scheduler pause reason=" + reason + " packetTick=" + packetTick);
            lastPauseReason = reason;
        }
    }

    private static void resume() {
        if (lastPauseReason != null) {
            MineDebugLog.write("scheduler resume after=" + lastPauseReason + " packetTick=" + packetTick);
            lastPauseReason = null;
        }
    }

    private static boolean pauseForInventoryState(String reasonPrefix) {
        boolean openHandler = isOpenHandler;
        boolean switchingItem = switchItem();
        if (openHandler || switchingItem) {
            pause(reasonPrefix + " openHandler=" + openHandler + " switchingItem=" + switchingItem);
            return true;
        }
        return false;
    }

    private static boolean pauseForPendingLookQueue() {
        if (!ActionManager.INSTANCE.needWaitModifyLook) {
            return false;
        }
        pause("send_queue_wait_modify_look");
        return true;
    }

    private static boolean pauseForLagCheck() {
        if (!Configs.Core.LAG_CHECK.getBooleanValue()) {
            return false;
        }
        if (packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
            pause("lag_check packetTick=" + packetTick + " max=" + Configs.Core.LAG_CHECK_MAX.getIntegerValue());
            return true;
        }
        packetTick++;
        return false;
    }

    private static boolean pauseForHandlerPrecheck(Module handler) {
        if (pauseForInventoryState("handler_precheck handler=" + handler.getId())) {
            return true;
        }
        if (ActionManager.INSTANCE.needWaitModifyLook) {
            pause("action_wait_modify_look handler=" + handler.getId());
            return true;
        }
        return false;
    }
}
