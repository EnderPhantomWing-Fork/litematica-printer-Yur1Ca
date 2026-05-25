package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBase;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicReference;

public class GuiHandler extends ClientPlayerTickHandler {
    public final static String NAME = "gui";

    @Getter
    private final Progress totalProgress = new Progress(Configs.Core.PRINT);
    @Getter
    private final Progress printProgress = new Progress(Configs.Core.PRINT);
    @Getter
    private final Progress fluidProgress = new Progress(Configs.Core.FLUID);
    @Getter
    private final Progress fillProgress = new Progress(Configs.Core.FILL);
    @Getter
    private final Progress mineProgress = new Progress(Configs.Core.MINE);
    @Getter
    private final Progress bedrockProgress = new Progress(Configs.Hotkeys.BEDROCK);

    private final Progress[] progresses = new Progress[]{totalProgress, printProgress, fluidProgress, fillProgress, mineProgress, bedrockProgress};

    public GuiHandler() {
        super(NAME, null, Configs.Core.RENDER_HUD, null, true);
    }

    @Override
    protected boolean isSchematicBlockHandler() {
        return ConfigUtils.isPrintMode();
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        if (!this.shouldUseMineTargetScan()) {
            return super.getIterationPositions(playerInteractionBox);
        }
        return this.getFilteredIterationPositions(playerInteractionBox, this::isMineProgressScanCandidate);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (ConfigUtils.isPrintMode()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            if (schematic != null) {
                SchematicBlockContext context = new SchematicBlockContext(client, level, schematic, blockPos);
                if (!context.requiredState.isAir()) {
                    if (BlockMatchResult.compare(context) != BlockMatchResult.MISSING) {
                        printProgress.finished++;
                        totalProgress.finished++;
                    }
                    printProgress.total++;
                    totalProgress.total++;
                }
            }
        }
        if (isFluidMode()) {
            if (!(level.getBlockState(blockPos).getBlock() instanceof LiquidBlock)) {
                fluidProgress.finished++;
                totalProgress.finished++;
            }
            fluidProgress.total++;
            totalProgress.total++;
        }
        if (isFillMode()) {
            if (!level.getBlockState(blockPos).isAir()) {
                fillProgress.finished++;
                totalProgress.finished++;
            }
            fillProgress.total++;
            totalProgress.total++;
        }
        if (isMineMode()) {
            if (level.getBlockState(blockPos).isAir()) {
                mineProgress.finished++;
                totalProgress.finished++;
            }
            mineProgress.total++;
            totalProgress.total++;
        }
        if (isBedrockMode()) {
            if (!BedrockTargetBlocks.isTargetBlock(level.getBlockState(blockPos))) {
                bedrockProgress.finished++;
                totalProgress.finished++;
            }
            bedrockProgress.total++;
            totalProgress.total++;
        }
        for (Progress progress : progresses) {
            progress.calculateProgress();
        }
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        this.publishHudProgress();
        if (!interrupt) {
            for (Progress progress : progresses) {
                progress.reset();
            }
        }
    }

    private void publishHudProgress() {
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.TOTAL, totalProgress.finished, totalProgress.total);
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.PRINT, printProgress.finished, printProgress.total);
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.FLUID, fluidProgress.finished, fluidProgress.total);
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.FILL, fillProgress.finished, fillProgress.total);
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.MINE, mineProgress.finished, mineProgress.total);
        HudStatsManager.INSTANCE.recordProgress(HudStatsManager.Mode.BEDROCK, bedrockProgress.finished, bedrockProgress.total);
    }

    private boolean shouldUseMineTargetScan() {
        return isMineMode()
                && !ConfigUtils.isPrintMode()
                && !isFillMode()
                && !isFluidMode()
                && !isBedrockMode();
    }

    private boolean isMineProgressScanCandidate(BlockPos pos) {
        if (pos == null || this.level == null || this.player == null || this.gameMode == null) {
            return false;
        }

        BlockState state = this.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            return false;
        }

        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && state.getBlock().defaultDestroyTime() < 0) {
            return false;
        }

        return this.canReachIterationPosition(pos)
                && !this.player.blockActionRestricted(this.level, pos, this.gameMode.getPlayerMode())
                && MineHandler.mineRestriction(state);
    }

    @Getter
    public static class Progress {
        private final ConfigBase<?> config;
        private long total;
        private long finished;
        private double progress;
        private double lastProgress;

        public Progress(ConfigBase<?> config) {
            this.config = config;
            this.total = 0;
            this.finished = 0;
            this.progress = 0.0;
            this.lastProgress = 0.0;
        }

        public double getProgress() {
            return progress <= 0 ? lastProgress : progress;
        }

        public void calculateProgress() {
            progress = total < 1 ? lastProgress : (float) finished / total;
            lastProgress = progress;
        }

        public void reset() {
            this.total = 0;
            this.finished = 0;
            this.progress = 0.0;
            this.lastProgress = 0.0;
        }
    }
}
