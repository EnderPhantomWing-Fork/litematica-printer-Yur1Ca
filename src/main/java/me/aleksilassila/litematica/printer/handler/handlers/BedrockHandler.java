package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEnvironment;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInventory;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class BedrockHandler extends ClientPlayerTickHandler {
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();

    public BedrockHandler() {
        super("bedrock", PrintModeType.BEDROCK, Configs.Hotkeys.BEDROCK, null, true);
    }

    @Override
    protected int getTickInterval() {
        return 0;
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Math.max(1, Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue());
    }

    @Override
    protected boolean canExecute() {
        if (player.isCreative()) {
            MessageUtils.setOverlayMessage(I18n.BEDROCK_CREATIVE_MODE.getName());
            return false;
        }
        String warning = BedrockInventory.warningMessage();
        if (warning != null) {
            MessageUtils.setOverlayMessage(me.aleksilassila.litematica.printer.utils.minecraft.StringUtils.translatable(warning));
            return false;
        }
        return true;
    }

    @Override
    protected boolean canIterate() {
        BedrockController.tick();
        return BedrockController.canScanForTargets();
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        if (playerInteractionBox == null || this.level == null || this.player == null) {
            return List.of();
        }

        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : playerInteractionBox) {
            if (pos == null || !ConfigUtils.canInteracted(pos)) {
                continue;
            }
            if (!BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(pos))) {
                continue;
            }
            positions.add(pos.immutable());
        }

        positions.sort(Comparator
                .comparingInt(this::candidatePriority)
                .thenComparingInt(this::neighborTargetCount)
                .thenComparingDouble(this::distanceSqToPlayer));
        return positions;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        return BedrockController.canAccept(pos);
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(blockPos))) {
            return;
        }
        if (BedrockController.submit(blockPos)) {
            // Allow a second same-tick submit when the controller still has safe capacity.
            skipIteration.set(!BedrockController.canScanForTargets());
        }
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (!interrupt) {
            BedrockController.tick();
        }
    }

    private int candidatePriority(BlockPos pos) {
        if (this.level == null) {
            return Integer.MAX_VALUE;
        }
        BedrockMachineLayout layout = BedrockMachineLayout.find(this.level, pos);
        if (layout != null) {
            int penalty = 0;
            if (BedrockMachineLayout.shouldDeferUntilExposed(this.level, pos)) {
                penalty += 100;
            }
            if (hasSlimeFallback(layout, pos)) {
                penalty += 10;
            }
            return penalty;
        }
        if (BedrockMachineLayout.shouldDeferUntilExposed(this.level, pos)) {
            return 1_000;
        }
        return 10_000;
    }

    private boolean hasSlimeFallback(BedrockMachineLayout layout, BlockPos bedrockPos) {
        if (this.level == null || layout == null) {
            return false;
        }
        return BedrockEnvironment.findTorchPlacement(
                this.level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        ) == null;
    }

    private int neighborTargetCount(BlockPos pos) {
        if (this.level == null) {
            return Integer.MAX_VALUE;
        }
        int count = 0;
        for (Direction direction : NEIGHBOR_DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            if (BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(neighborPos))) {
                count++;
            }
        }
        return count;
    }

    private double distanceSqToPlayer(BlockPos pos) {
        if (this.player == null) {
            return Double.MAX_VALUE;
        }
        return this.player.position().distanceToSqr(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5
        );
    }
}
