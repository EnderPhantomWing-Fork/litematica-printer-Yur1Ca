package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEnvironment;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInventory;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTorchPlacement;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class BedrockHandler extends ClientPlayerTickHandler {
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();
    private static final int CANDIDATE_SOFT_CAP = 256;
    private int candidateScanOffset = 0;
    private BlockPos lastCandidatePlayerPos;

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

        List<CandidateInfo> candidates = new ArrayList<>();
        for (BlockPos pos : playerInteractionBox) {
            if (pos == null) {
                continue;
            }
            if (!BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(pos))) {
                continue;
            }
            if (BedrockController.isPositionOnRetryCooldown(pos)) {
                continue;
            }
            CandidateInfo candidate = buildCandidate(pos.immutable());
            if (!canReachCandidate(candidate)) {
                continue;
            }
            candidates.add(candidate);
        }

        if (candidates.size() <= 1) {
            List<BlockPos> single = new ArrayList<>(candidates.size());
            for (CandidateInfo candidate : candidates) {
                single.add(candidate.pos());
            }
            return single;
        }

        candidates.sort(Comparator
                .<CandidateInfo>comparingInt(candidate -> layoutPriority(candidate.layout()))
                .thenComparingInt(CandidateInfo::priority)
                .thenComparingInt(CandidateInfo::neighborTargetCount)
                .thenComparingDouble(CandidateInfo::distanceSqToPlayer));

        int limit = Math.min(candidates.size(), CANDIDATE_SOFT_CAP);
        List<CandidateInfo> selectedCandidates = selectCandidateWindow(candidates, limit);
        List<BlockPos> filtered = new ArrayList<>(selectedCandidates.size());
        for (CandidateInfo candidate : selectedCandidates) {
            filtered.add(candidate.pos());
        }
        return filtered;
    }

    @Override
    protected boolean canReachIterationPosition(BlockPos pos) {
        return canReachCandidate(pos, null, null);
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
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        boolean submitted = BedrockController.submit(blockPos);
        setIterationConsumedEffectiveExecution(submitted);
        if (submitted) {
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

    private CandidateInfo buildCandidate(BlockPos pos) {
        if (this.level == null) {
            return new CandidateInfo(pos, null, null, List.of(), List.of(), Integer.MAX_VALUE, Integer.MAX_VALUE, Double.MAX_VALUE);
        }

        BedrockMachineLayout layout = BedrockMachineLayout.find(this.level, pos);
        BedrockTorchPlacement placement = layout == null ? null : findPlacement(layout, pos);
        int priority = candidatePriority(pos, layout, placement);
        int neighborTargetCount = neighborTargetCount(pos);
        double distanceSqToPlayer = distanceSqToPlayer(pos);
        return new CandidateInfo(
                pos,
                layout,
                placement,
                buildStructuralPositions(pos, layout),
                buildPowerReservationPositions(placement),
                priority,
                neighborTargetCount,
                distanceSqToPlayer
        );
    }

    private int candidatePriority(BlockPos pos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
        if (this.level == null) {
            return Integer.MAX_VALUE;
        }
        int controllerPenalty = BedrockController.getSchedulingPenalty(pos);
        if (layout != null) {
            int penalty = controllerPenalty;
            penalty += BedrockController.getSchedulingPenalty(layout.getPistonPos());
            penalty += BedrockController.getSchedulingPenalty(layout.getHeadPos());
            if (placement != null) {
                penalty += BedrockController.getSchedulingPenalty(placement.getSupportPos());
                penalty += BedrockController.getSchedulingPenalty(placement.getTorchPos());
                if (this.level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)) {
                    penalty += 200;
                }
            }
            penalty += BedrockController.getPredictedMachineOverlapPenalty(pos, layout, placement);
            return penalty;
        }
        if (BedrockMachineLayout.shouldDeferUntilExposed(this.level, pos)) {
            return controllerPenalty + 1_000;
        }
        return controllerPenalty + 10_000;
    }

    private List<CandidateInfo> selectCandidateWindow(List<CandidateInfo> candidates, int limit) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= limit) {
            this.candidateScanOffset = 0;
            this.lastCandidatePlayerPos = this.player == null ? null : this.player.blockPosition().immutable();
            return selectNonConflictingCandidates(candidates, limit);
        }

        BlockPos playerPos = this.player == null ? null : this.player.blockPosition().immutable();
        if (playerPos == null || !playerPos.equals(this.lastCandidatePlayerPos)) {
            this.candidateScanOffset = 0;
            this.lastCandidatePlayerPos = playerPos;
        }

        int size = candidates.size();
        int start = Math.floorMod(this.candidateScanOffset, size);
        List<CandidateInfo> ordered = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ordered.add(candidates.get((start + index) % size));
        }

        List<CandidateInfo> selected = selectNonConflictingCandidates(ordered, limit);
        int overflow = size - limit;
        int advance = Math.max(1, Math.min(Math.max(1, limit / 2), overflow));
        this.candidateScanOffset = (start + advance) % size;
        return selected;
    }

    private List<CandidateInfo> selectNonConflictingCandidates(List<CandidateInfo> candidates, int limit) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        List<CandidateInfo> selected = new ArrayList<>(limit);
        for (CandidateInfo candidate : candidates) {
            if (selected.size() >= limit) {
                break;
            }
            if (conflictsWithSelected(candidate, selected)) {
                continue;
            }
            selected.add(candidate);
        }
        return selected;
    }

    private boolean conflictsWithSelected(CandidateInfo candidate, List<CandidateInfo> selected) {
        for (CandidateInfo existing : selected) {
            if (candidatesConflict(candidate, existing)) {
                return true;
            }
        }
        return false;
    }

    private boolean candidatesConflict(CandidateInfo left, CandidateInfo right) {
        if (left.layout() == null || right.layout() == null) {
            return false;
        }
        if (intersects(left.structuralPositions(), right.structuralPositions())
                || intersects(left.structuralPositions(), right.powerReservationPositions())
                || intersects(left.powerReservationPositions(), right.structuralPositions())
                || intersects(left.powerReservationPositions(), right.powerReservationPositions())) {
            return true;
        }
        if (left.placement() != null && right.placement() != null
                && sameTorchPlacement(left.placement(), right.placement())) {
            return true;
        }
        return isTorchPoweredBy(left.layout().getPistonPos(), right.placement())
                || isTorchPoweredBy(right.layout().getPistonPos(), left.placement());
    }

    private int layoutPriority(BedrockMachineLayout layout) {
        if (layout == null) {
            return Integer.MAX_VALUE;
        }
        Direction offset = layout.getPistonOffset();
        if (offset.getAxis().isHorizontal()) {
            return 0;
        }
        if (offset == Direction.UP) {
            return 1;
        }
        return 2;
    }

    private boolean intersects(Iterable<BlockPos> left, Iterable<BlockPos> right) {
        for (BlockPos leftPos : left) {
            for (BlockPos rightPos : right) {
                if (leftPos.equals(rightPos)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canReachCandidate(CandidateInfo candidate) {
        if (candidate == null) {
            return false;
        }
        return canReachCandidate(candidate.pos(), candidate.layout(), candidate.placement());
    }

    private boolean canReachCandidate(BlockPos pos, BedrockMachineLayout layout, BedrockTorchPlacement placement) {
        if (this.level == null || pos == null || !BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(pos))) {
            return false;
        }

        BedrockMachineLayout resolvedLayout = layout != null ? layout : BedrockMachineLayout.find(this.level, pos);
        if (resolvedLayout == null) {
            return false;
        }

        BedrockTorchPlacement resolvedPlacement = placement != null ? placement : findPlacement(resolvedLayout, pos);
        if (resolvedPlacement == null) {
            return false;
        }

        if (!BedrockEnvironment.arePositionsInteractable(resolvedPlacement.getSupportPos())) {
            return false;
        }

        return BedrockEnvironment.findPlacementInteraction(
                this.level,
                resolvedLayout.getPistonPos(),
                pos,
                resolvedPlacement.getSupportPos(),
                resolvedPlacement.getTorchPos()) != null;
    }

    private List<BlockPos> buildStructuralPositions(BlockPos bedrockPos, BedrockMachineLayout layout) {
        List<BlockPos> positions = new ArrayList<>(3);
        positions.add(bedrockPos);
        if (layout != null) {
            positions.add(layout.getPistonPos());
            positions.add(layout.getHeadPos());
        }
        return positions;
    }

    private List<BlockPos> buildPowerReservationPositions(BedrockTorchPlacement placement) {
        if (placement == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(2);
        if (placement.getSupportPos() != null) {
            positions.add(placement.getSupportPos());
        }
        if (placement.getTorchPos() != null) {
            positions.add(placement.getTorchPos());
        }
        return positions;
    }

    private boolean sameTorchPlacement(BedrockTorchPlacement left, BedrockTorchPlacement right) {
        return left.getClickedFace() == right.getClickedFace()
                && left.getSupportPos() != null
                && left.getSupportPos().equals(right.getSupportPos())
                && left.getTorchPos() != null
                && left.getTorchPos().equals(right.getTorchPos());
    }

    private boolean isTorchPoweredBy(BlockPos pistonPos, BedrockTorchPlacement placement) {
        return pistonPos != null
                && placement != null
                && placement.getTorchPos() != null
                && BedrockEnvironment.getTorchInfluencePositions(pistonPos).contains(placement.getTorchPos());
    }

    private BedrockTorchPlacement findPlacement(BedrockMachineLayout layout, BlockPos bedrockPos) {
        if (this.level == null || layout == null) {
            return null;
        }
        BedrockTorchPlacement placement = BedrockEnvironment.findTorchPlacement(
                this.level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        if (placement != null) {
            return placement;
        }
        return BedrockEnvironment.findPossibleSlimeTorchPlacement(
                this.level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
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

    private record CandidateInfo(
            BlockPos pos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            List<BlockPos> structuralPositions,
            List<BlockPos> powerReservationPositions,
            int priority,
            int neighborTargetCount,
            double distanceSqToPlayer
    ) {
    }

}
