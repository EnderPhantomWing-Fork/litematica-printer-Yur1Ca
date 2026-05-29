package me.aleksilassila.litematica.printer.handler;

import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.function.Predicate;

final class FilteredBlockPositions {
    private FilteredBlockPositions() {
    }

    static Iterable<BlockPos> create(Iterator<BlockPos> source, int scanLimit, Predicate<BlockPos> predicate) {
        return () -> new Iterator<>() {
            private BlockPos next;
            private boolean prepared;
            private boolean scanLimitHit;
            private boolean sentinelReturned;
            private int scanned;

            private void prepare() {
                if (this.prepared) {
                    return;
                }

                this.prepared = true;
                while (source.hasNext() && this.scanned < scanLimit) {
                    BlockPos candidate = source.next();
                    this.scanned++;
                    if (predicate.test(candidate)) {
                        this.next = candidate;
                        return;
                    }
                }

                this.scanLimitHit = source.hasNext() && this.scanned >= scanLimit;
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.next != null || this.scanLimitHit && !this.sentinelReturned;
            }

            @Override
            public BlockPos next() {
                this.prepare();
                if (this.next != null) {
                    BlockPos result = this.next;
                    this.next = null;
                    this.prepared = false;
                    return result;
                }

                if (this.scanLimitHit && !this.sentinelReturned) {
                    this.sentinelReturned = true;
                    return null;
                }

                return null;
            }
        };
    }
}
