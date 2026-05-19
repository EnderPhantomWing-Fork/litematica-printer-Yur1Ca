package me.aleksilassila.litematica.printer.render;

import fi.dy.masa.malilib.config.options.ConfigColor;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.printer.zxy.utils.HighlightBlockRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class WorkTargetHighlighter {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final HudStatsManager.Mode[] MODES = {
            HudStatsManager.Mode.PRINT,
            HudStatsManager.Mode.MINE,
            HudStatsManager.Mode.FILL,
            HudStatsManager.Mode.FLUID,
            HudStatsManager.Mode.BEDROCK
    };

    private static final EnumMap<HudStatsManager.Mode, Map<BlockPos, Long>> TARGETS = new EnumMap<>(HudStatsManager.Mode.class);
    private static boolean rendered;

    private WorkTargetHighlighter() {
    }

    public static void init() {
        for (HudStatsManager.Mode mode : MODES) {
            HighlightBlockRenderer.createHighlightBlockList(id(mode), color(mode));
            TARGETS.put(mode, new LinkedHashMap<>());
        }
    }

    public static void record(HudStatsManager.Mode mode, BlockPos pos) {
        if (!Configs.Core.RENDER_WORK_TARGETS.getBooleanValue() || pos == null || !TARGETS.containsKey(mode)) {
            return;
        }
        long expireTick = ClientPlayerTickManager.getCurrentHandlerTime()
                + Math.max(1, Configs.Core.RENDER_WORK_TARGETS_DURATION.getIntegerValue());
        TARGETS.get(mode).put(pos.immutable(), expireTick);
    }

    public static void tick(long currentTick) {
        if (!Configs.Core.RENDER_WORK_TARGETS.getBooleanValue() || CLIENT.level == null || CLIENT.player == null) {
            clear();
            return;
        }

        for (HudStatsManager.Mode mode : MODES) {
            Map<BlockPos, Long> targets = TARGETS.get(mode);
            if (targets == null) {
                continue;
            }
            targets.entrySet().removeIf(entry -> currentTick > entry.getValue());
            Set<BlockPos> positions = new LinkedHashSet<>(targets.keySet());
            HighlightBlockRenderer.setPos(id(mode), positions);
        }
        rendered = true;
    }

    private static void clear() {
        if (!rendered && TARGETS.values().stream().allMatch(Map::isEmpty)) {
            return;
        }
        for (HudStatsManager.Mode mode : MODES) {
            Map<BlockPos, Long> targets = TARGETS.get(mode);
            if (targets != null) {
                targets.clear();
            }
            HighlightBlockRenderer.clear(id(mode));
        }
        rendered = false;
    }

    private static String id(HudStatsManager.Mode mode) {
        return "work_target_" + mode.name().toLowerCase();
    }

    private static ConfigColor color(HudStatsManager.Mode mode) {
        return switch (mode) {
            case PRINT -> Configs.Core.RENDER_WORK_TARGETS_PRINT_COLOR;
            case MINE -> Configs.Core.RENDER_WORK_TARGETS_MINE_COLOR;
            case FILL -> Configs.Core.RENDER_WORK_TARGETS_FILL_COLOR;
            case FLUID -> Configs.Core.RENDER_WORK_TARGETS_FLUID_COLOR;
            case BEDROCK -> Configs.Core.RENDER_WORK_TARGETS_BEDROCK_COLOR;
            default -> Configs.Core.RENDER_WORK_TARGETS_PRINT_COLOR;
        };
    }
}
