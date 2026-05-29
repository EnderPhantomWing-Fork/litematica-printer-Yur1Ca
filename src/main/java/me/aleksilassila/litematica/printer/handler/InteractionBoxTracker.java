package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.IterationOrderType;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

final class InteractionBoxTracker {
    @Nullable
    private final AtomicReference<PrinterBox> boxReference;
    @Nullable
    private PrinterBox lastBox;
    @Nullable
    private BlockPos lastPlayerPos;

    InteractionBoxTracker(boolean enabled) {
        this.boxReference = enabled ? new AtomicReference<>() : null;
    }

    @Nullable
    AtomicReference<PrinterBox> getBoxReference() {
        return this.boxReference;
    }

    void resetPlayerTracking() {
        this.lastPlayerPos = null;
    }

    void update(LocalPlayer player) {
        if (this.boxReference == null) {
            return;
        }
        BlockPos playerPos = player.blockPosition();
        double threshold = ConfigUtils.getWorkRange() * 0.7;
        @Nullable PrinterBox box = this.boxReference.get();
        if (box == null
                || !box.equals(this.lastBox)
                || this.lastPlayerPos == null
                || !this.lastPlayerPos.closerThan(playerPos, threshold)) {
            this.lastPlayerPos = playerPos;
            box = this.createBox(playerPos);
            this.lastBox = box;
            this.boxReference.set(box);
        }
        syncIterationConfig(box);
    }

    private PrinterBox createBox(BlockPos playerPos) {
        PrinterBox box = new PrinterBox(playerPos);
        if (Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()) {
            return box.expand((int) Math.ceil(PlayerUtils.getPlayerBlockInteractionRange(5) + 3));
        }
        return box.expand(ConfigUtils.getWorkRange());
    }

    private static void syncIterationConfig(PrinterBox box) {
        box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
        box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
        box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
        box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();
    }
}
