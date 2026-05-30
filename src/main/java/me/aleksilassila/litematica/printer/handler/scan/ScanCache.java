package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ScanCache {
    public static final ScanCache INSTANCE = new ScanCache();

    private static final int UNLIMITED_SCAN_SLICE = 4096;
    private static final int MAX_SCAN_SLICE = 32768;
    private static final int MAX_ASYNC_PREFIX = 256;
    private static final int MAX_CACHE_ENTRIES = 131_072;
    private static final int ENTRY_TTL_TICKS = 40;

    private final Map<Long, Entry> entries = new HashMap<>();
    private final AsyncScanCandidatePlanner asyncPlanner = new AsyncScanCandidatePlanner();

    private Object levelIdentity;
    private Object schematicIdentity;
    private long tickTime = Long.MIN_VALUE;

    private ScanCache() {
    }

    public static long key(BlockPos pos) {
        return key(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    public void beginTick(ClientLevel level, WorldSchematic schematic, long tickTime) {
        if (this.levelIdentity == level && this.schematicIdentity == schematic && this.tickTime == tickTime) {
            return;
        }
        if (this.levelIdentity != level || this.schematicIdentity != schematic) {
            this.entries.clear();
            this.asyncPlanner.clear();
            this.levelIdentity = level;
            this.schematicIdentity = schematic;
        }
        this.tickTime = tickTime;
        if (tickTime % 20L == 0L) {
            this.prune();
        }
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int maxTotalIterations,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate
    ) {
        return this.iterable(ownerKey, sourceBox, level, schematic, player, maxTotalIterations, intent, exactPredicate, pos -> true);
    }

    public Iterable<BlockPos> iterable(
            String ownerKey,
            PrinterBox sourceBox,
            ClientLevel level,
            WorldSchematic schematic,
            LocalPlayer player,
            int maxTotalIterations,
            ScanIntent intent,
            Predicate<BlockPos> exactPredicate,
            Predicate<BlockPos> preFilter
    ) {
        int scanLimit = this.getScanLimit(maxTotalIterations);
        int asyncLimit = Math.min(MAX_ASYNC_PREFIX, Math.max(0, scanLimit / 4));
        List<BlockPos> asyncPositions = this.asyncPlanner.take(ownerKey, sourceBox, asyncLimit);
        double eyeX = player == null ? 0.0D : player.getEyePosition().x;
        double eyeY = player == null ? 0.0D : player.getEyePosition().y;
        double eyeZ = player == null ? 0.0D : player.getEyePosition().z;

        return () -> new Iterator<>() {
            private final Iterator<BlockPos> asyncIterator = asyncPositions.iterator();
            private final Iterator<BlockPos> sourceIterator = sourceBox.iterator();
            private final Set<Long> emitted = new HashSet<>();
            private List<ScanSnapshot> targetSnapshots = new ArrayList<>();
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

                while (this.asyncIterator.hasNext()) {
                    BlockPos pos = this.asyncIterator.next();
                    if (preFilter.test(pos) && this.emitted.add(key(pos))) {
                        this.next = pos;
                        return;
                    }
                }

                while (this.sourceIterator.hasNext() && this.scanned < scanLimit) {
                    BlockPos pos = this.sourceIterator.next();
                    this.scanned++;
                    if (!preFilter.test(pos)) {
                        continue;
                    }
                    Entry entry = sample(level, schematic, pos, intent == ScanIntent.PRINT);
                    byte flags = entry.flags();
                    if (!intent.shouldConsider(flags)) {
                        continue;
                    }
                    boolean target = intent.acceptsByFlags(flags);
                    if (!target && intent.shouldRunExactPredicate(flags)) {
                        target = exactPredicate.test(pos);
                    }
                    if (!target) {
                        continue;
                    }
                    long posKey = key(pos);
                    byte targetFlags = (byte) (flags | ScanFlags.TARGET);
                    this.targetSnapshots.add(new ScanSnapshot(posKey, pos.getX(), pos.getY(), pos.getZ(), targetFlags));
                    this.submitSnapshotsIfNeeded(false);
                    if (this.emitted.add(posKey)) {
                        this.next = pos;
                        return;
                    }
                }

                this.submitSnapshotsIfNeeded(true);
                this.scanLimitHit = this.sourceIterator.hasNext() && this.scanned >= scanLimit;
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

            private void submitSnapshotsIfNeeded(boolean force) {
                if (player == null || this.targetSnapshots.isEmpty()) {
                    return;
                }
                if (!force && this.targetSnapshots.size() < 64) {
                    return;
                }
                asyncPlanner.submit(ownerKey, this.targetSnapshots, eyeX, eyeY, eyeZ);
                this.targetSnapshots = new ArrayList<>();
            }
        };
    }

    private Entry sample(ClientLevel level, WorldSchematic schematic, BlockPos pos, boolean sampleSchematic) {
        long posKey = key(pos);
        Entry cached = this.entries.get(posKey);
        if (cached != null && this.tickTime - cached.tickTime() <= ENTRY_TTL_TICKS) {
            if (!sampleSchematic || ScanFlags.has(cached.flags(), ScanFlags.SCHEMATIC_SAMPLED)) {
                return cached;
            }
        }

        byte flags = cached == null ? 0 : cached.flags();
        BlockState worldState = level.getBlockState(pos);
        flags &= ~(ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID | ScanFlags.BASE_FILL_TARGET);
        if (!worldState.isAir()) {
            flags |= ScanFlags.WORLD_NON_AIR;
        }
        if (!worldState.getFluidState().isEmpty()) {
            flags |= ScanFlags.WORLD_FLUID;
        }
        if (worldState.isAir() || !worldState.getFluidState().isEmpty()) {
            flags |= ScanFlags.BASE_FILL_TARGET;
        }
        if (sampleSchematic) {
            flags &= ~ScanFlags.SCHEMATIC_NON_AIR;
            flags |= ScanFlags.SCHEMATIC_SAMPLED;
            if (schematic != null && !schematic.getBlockState(pos).isAir()) {
                flags |= ScanFlags.SCHEMATIC_NON_AIR;
            }
        }

        Entry entry = new Entry(flags, this.tickTime);
        if ((flags & (ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID | ScanFlags.SCHEMATIC_NON_AIR)) != 0) {
            this.entries.put(posKey, entry);
        } else {
            this.entries.remove(posKey);
        }
        return entry;
    }

    private int getScanLimit(int maxTotalIterations) {
        int scanLimit = maxTotalIterations > 0 ? maxTotalIterations : UNLIMITED_SCAN_SLICE;
        return Math.max(1, Math.min(MAX_SCAN_SLICE, scanLimit));
    }

    private void prune() {
        if (this.entries.isEmpty()) {
            return;
        }
        long minTick = this.tickTime - ENTRY_TTL_TICKS;
        this.entries.entrySet().removeIf(entry -> entry.getValue().tickTime() < minTick);
        if (this.entries.size() <= MAX_CACHE_ENTRIES) {
            return;
        }
        int removeCount = this.entries.size() - MAX_CACHE_ENTRIES;
        Iterator<Long> iterator = this.entries.keySet().iterator();
        while (iterator.hasNext() && removeCount-- > 0) {
            iterator.next();
            iterator.remove();
        }
    }

    private record Entry(byte flags, long tickTime) {
    }
}
