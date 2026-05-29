package me.aleksilassila.litematica.printer.handler.handlers.print;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class SortedSchematicTargetQueue {
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private PrinterBox box;
    private boolean hasMoreSource;

    public void clear() {
        this.queue.clear();
        this.box = null;
        this.hasMoreSource = false;
    }

    public Iterable<BlockPos> iterable(PrinterBox sourceBox, WorldSchematic schematic, LocalPlayer player, int maxTotalIterations) {
        if (this.box != sourceBox) {
            this.queue.clear();
            this.box = sourceBox;
        }
        this.fill(sourceBox, schematic, player, maxTotalIterations);
        return this::iterator;
    }

    private void fill(PrinterBox sourceBox, WorldSchematic schematic, LocalPlayer player, int maxTotalIterations) {
        int collectLimit = maxTotalIterations > 0 ? maxTotalIterations : Integer.MAX_VALUE;
        List<BlockPos> positions = new ArrayList<>();
        while (!this.queue.isEmpty()) {
            positions.add(this.queue.removeFirst());
        }
        Iterator<BlockPos> source = sourceBox.iterator();
        int scanned = 0;
        while (source.hasNext() && positions.size() < collectLimit && scanned < collectLimit) {
            BlockPos candidate = source.next();
            scanned++;
            if (!schematic.getBlockState(candidate).isAir()) {
                positions.add(candidate);
            }
        }
        positions.sort(createComparator(schematic, player));
        this.queue.addAll(positions);
        this.hasMoreSource = maxTotalIterations > 0 && source.hasNext();
    }

    private Iterator<BlockPos> iterator() {
        return new Iterator<>() {
            private boolean returnedSentinel;

            @Override
            public boolean hasNext() {
                return !queue.isEmpty() || hasMoreSource && !this.returnedSentinel;
            }

            @Override
            public BlockPos next() {
                if (!queue.isEmpty()) {
                    return queue.removeFirst();
                }
                this.returnedSentinel = true;
                return null;
            }
        };
    }

    private static Comparator<BlockPos> createComparator(WorldSchematic schematic, LocalPlayer player) {
        Item heldItem = player.getMainHandItem().getItem();
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getLookAngle().normalize();
        return Comparator
                .comparing((BlockPos pos) -> !isHoldingRequiredItem(schematic, heldItem, pos))
                .thenComparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye))
                .thenComparingDouble(pos -> getViewAngleScore(eye, view, pos));
    }

    private static boolean isHoldingRequiredItem(WorldSchematic schematic, Item heldItem, BlockPos pos) {
        return schematic.getBlockState(pos).getBlock().asItem() == heldItem;
    }

    private static double getViewAngleScore(Vec3 eye, Vec3 view, BlockPos pos) {
        Vec3 toTarget = Vec3.atCenterOf(pos).subtract(eye);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return 0.0D;
        }
        return -view.dot(toTarget.normalize());
    }
}
