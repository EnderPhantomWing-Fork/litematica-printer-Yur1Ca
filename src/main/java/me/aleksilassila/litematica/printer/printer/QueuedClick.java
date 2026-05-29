package me.aleksilassila.litematica.printer.printer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

final class QueuedClick {
    final BlockPos target;
    final Direction side;
    Vec3 hitModifier;
    final boolean useShift;
    boolean useProtocol;
    final int repeatCount;

    QueuedClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int repeatCount) {
        this.target = target;
        this.side = side;
        this.hitModifier = hitModifier;
        this.useShift = useShift;
        this.repeatCount = Math.max(1, repeatCount);
    }

    void useProtocolHit(Vec3 hitModifier) {
        this.hitModifier = hitModifier;
        this.useProtocol = true;
    }
}
