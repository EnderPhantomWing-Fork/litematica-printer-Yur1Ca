package me.aleksilassila.litematica.printer.handler;

import java.util.EnumMap;

public final class HudStatsManager {
    public static final HudStatsManager INSTANCE = new HudStatsManager();
    private static final int RATE_WINDOW_TICKS = 20;

    private final EnumMap<Mode, ModeStats> stats = new EnumMap<>(Mode.class);

    private HudStatsManager() {
        for (Mode mode : Mode.values()) {
            this.stats.put(mode, new ModeStats());
        }
    }

    public void resetAll() {
        for (Mode mode : Mode.values()) {
            this.resetMode(mode);
        }
    }

    public void resetMode(Mode mode) {
        this.stats.get(mode).reset();
    }

    public void recordProgress(Mode mode, long finished, long total) {
        this.stats.get(mode).recordProgress(finished, total);
    }

    public void recordRateUnit(Mode mode, int count) {
        if (count <= 0) {
            return;
        }
        this.stats.get(mode).recordRateUnit(ClientPlayerTickManager.getCurrentHandlerTime(), count);
    }

    public void recordFailure(Mode mode, String reason) {
        this.stats.get(mode).recordFailure(ClientPlayerTickManager.getCurrentHandlerTime(), reason);
    }

    public void recordDeferred(Mode mode, String reason) {
        this.stats.get(mode).recordDeferred(ClientPlayerTickManager.getCurrentHandlerTime(), reason);
    }

    public void recordStatus(Mode mode, String reason) {
        this.stats.get(mode).recordStatus(reason);
    }

    public Snapshot snapshot(Mode mode) {
        return this.stats.get(mode).snapshot(ClientPlayerTickManager.getCurrentHandlerTime());
    }

    public enum Mode {
        TOTAL,
        PRINT,
        MINE,
        FILL,
        FLUID,
        BEDROCK
    }

    public record Snapshot(
            long finished,
            long total,
            double progress,
            double ratePerSecond,
            double failuresPerSecond,
            double deferredPerSecond,
            long lifetimeUnits,
            long lifetimeFailures,
            long lifetimeDeferred,
            String lastReason
    ) {
    }

    private static final class ModeStats {
        private final RollingCounter rateCounter = new RollingCounter();
        private final RollingCounter failureCounter = new RollingCounter();
        private final RollingCounter deferredCounter = new RollingCounter();

        private long finished;
        private long total;
        private double progress;
        private long lifetimeUnits;
        private long lifetimeFailures;
        private long lifetimeDeferred;
        private String lastReason = "空闲";

        private void reset() {
            this.finished = 0;
            this.total = 0;
            this.progress = 0.0D;
            this.lifetimeUnits = 0;
            this.lifetimeFailures = 0;
            this.lifetimeDeferred = 0;
            this.lastReason = "空闲";
            this.rateCounter.reset();
            this.failureCounter.reset();
            this.deferredCounter.reset();
        }

        private void recordProgress(long finished, long total) {
            this.finished = Math.max(0L, finished);
            this.total = Math.max(0L, total);
            this.progress = this.total > 0 ? (double) this.finished / (double) this.total : 0.0D;
        }

        private void recordRateUnit(long tick, int count) {
            this.rateCounter.add(tick, count);
            this.lifetimeUnits += count;
            this.lastReason = "运行中";
        }

        private void recordFailure(long tick, String reason) {
            this.failureCounter.add(tick, 1);
            this.lifetimeFailures++;
            this.lastReason = normalizeReason(reason);
        }

        private void recordDeferred(long tick, String reason) {
            this.deferredCounter.add(tick, 1);
            this.lifetimeDeferred++;
            this.lastReason = normalizeReason(reason);
        }

        private void recordStatus(String reason) {
            this.lastReason = normalizeReason(reason);
        }

        private Snapshot snapshot(long now) {
            return new Snapshot(
                    this.finished,
                    this.total,
                    this.progress,
                    this.rateCounter.sumRecent(now),
                    this.failureCounter.sumRecent(now),
                    this.deferredCounter.sumRecent(now),
                    this.lifetimeUnits,
                    this.lifetimeFailures,
                    this.lifetimeDeferred,
                    this.lastReason
            );
        }

        private static String normalizeReason(String reason) {
            return reason == null || reason.isBlank() ? "运行中" : reason;
        }
    }

    private static final class RollingCounter {
        private final long[] ticks = new long[RATE_WINDOW_TICKS];
        private final int[] values = new int[RATE_WINDOW_TICKS];

        private RollingCounter() {
            this.reset();
        }

        private void reset() {
            for (int i = 0; i < RATE_WINDOW_TICKS; i++) {
                this.ticks[i] = Long.MIN_VALUE;
                this.values[i] = 0;
            }
        }

        private void add(long tick, int delta) {
            int slot = Math.floorMod((int) tick, RATE_WINDOW_TICKS);
            if (this.ticks[slot] != tick) {
                this.ticks[slot] = tick;
                this.values[slot] = 0;
            }
            this.values[slot] += delta;
        }

        private double sumRecent(long now) {
            int total = 0;
            for (int i = 0; i < RATE_WINDOW_TICKS; i++) {
                if (this.ticks[i] != Long.MIN_VALUE && now - this.ticks[i] < RATE_WINDOW_TICKS) {
                    total += this.values[i];
                }
            }
            return total;
        }
    }
}
