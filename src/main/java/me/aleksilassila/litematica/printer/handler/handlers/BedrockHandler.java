package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockController;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockDebugLog;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockEnvironment;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockMachineLayout;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInventory;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTorchPlacement;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockTargetBlocks;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
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
        BedrockController.clearSubmissionPlans();
        if (playerInteractionBox == null || this.level == null || this.player == null) {
            return List.of();
        }

        PrinterBox scanBox = snapshotIterationBox(playerInteractionBox);
        List<CandidateInfo> candidates = new ArrayList<>();
        for (BlockPos pos : scanBox) {
            if (pos == null || !BedrockEnvironment.canInteract(pos)) {
                continue;
            }
            if (!LitematicaUtils.isWithinSelection1ModeRange(pos)) {
                continue;
            }
            if (!BedrockTargetBlocks.isTargetBlock(this.level.getBlockState(pos))) {
                continue;
            }
            if (BedrockController.isPositionOnRetryCooldown(pos)) {
                continue;
            }
            candidates.add(buildCandidate(pos.immutable()));
        }

        logIsolatedCandidateSummary(candidates);

        if (candidates.size() <= 1) {
            List<BlockPos> single = new ArrayList<>(candidates.size());
            for (CandidateInfo candidate : candidates) {
                BedrockController.primeSubmissionPlan(candidate.pos(), candidate.layout(), candidate.placement(), candidate.slimePos());
                single.add(candidate.pos());
            }
            return single;
        }

        candidates.sort(Comparator
                .comparingInt(CandidateInfo::priority)
                .thenComparingInt(CandidateInfo::neighborTargetCount));

        int limit = Math.min(candidates.size(), CANDIDATE_SOFT_CAP);
        List<CandidateInfo> selectedCandidates = selectNonConflictingCandidates(candidates, limit);
        logIsolatedCandidateSelection(candidates, selectedCandidates, limit);
        List<BlockPos> filtered = new ArrayList<>(selectedCandidates.size());
        for (CandidateInfo candidate : selectedCandidates) {
            BedrockController.primeSubmissionPlan(candidate.pos(), candidate.layout(), candidate.placement(), candidate.slimePos());
            filtered.add(candidate.pos());
        }
        return filtered;
    }

    private PrinterBox snapshotIterationBox(PrinterBox source) {
        PrinterBox snapshot = new PrinterBox(
                source.minX,
                source.minY,
                source.minZ,
                source.maxX,
                source.maxY,
                source.maxZ
        );
        snapshot.iterationMode = source.iterationMode;
        snapshot.xIncrement = source.xIncrement;
        snapshot.yIncrement = source.yIncrement;
        snapshot.zIncrement = source.zIncrement;
        return snapshot;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos pos) {
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        return BedrockController.canAccept(pos);
    }

    @Override
    protected boolean canReachIterationPosition(BlockPos pos) {
        return BedrockEnvironment.canInteract(pos);
    }

    @Override
    protected boolean requiresSelection1ModeRangeCheck() {
        return false;
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
            return new CandidateInfo(pos, null, null, null, List.of(), List.of(), Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        BedrockMachineLayout layout = BedrockMachineLayout.find(this.level, pos);
        PlacementSelection placementSelection = layout == null ? null : findPlacementSelection(layout, pos);
        BedrockTorchPlacement placement = placementSelection == null ? null : placementSelection.placement();
        BlockPos slimePos = placementSelection == null ? null : placementSelection.slimePos();
        int priority = candidatePriority(pos, layout, placement);
        int neighborTargetCount = neighborTargetCount(pos);
        return new CandidateInfo(
                pos,
                layout,
                placement,
                slimePos,
                buildStructuralPositions(pos, layout),
                buildPowerReservationPositions(placement),
                priority,
                neighborTargetCount
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

    private void logIsolatedCandidateSummary(List<CandidateInfo> candidates) {
        CandidateInfo isolated = findBestIsolatedCandidate(candidates);
        if (isolated == null) {
            return;
        }
        String acceptReason = BedrockController.debugCanAcceptReason(isolated.pos());
        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("isolated scan pos=" + BedrockDebugLog.pos(isolated.pos())
                    + " priority=" + isolated.priority()
                    + " neighbors=" + isolated.neighborTargetCount()
                    + " layout=" + (isolated.layout() != null ? isolated.layout().getPistonOffset() : "null")
                    + " piston=" + BedrockDebugLog.pos(isolated.layout() != null ? isolated.layout().getPistonPos() : null)
                    + " torchSupport=" + BedrockDebugLog.pos(isolated.placement() != null ? isolated.placement().getSupportPos() : null)
                    + " torch=" + BedrockDebugLog.pos(isolated.placement() != null ? isolated.placement().getTorchPos() : null)
                    + " canAccept=" + acceptReason
                    + " candidates=" + candidates.size());
        }
    }

    private void logIsolatedCandidateSelection(List<CandidateInfo> candidates, List<CandidateInfo> selectedCandidates, int limit) {
        CandidateInfo isolated = findBestIsolatedCandidate(candidates);
        if (isolated == null) {
            return;
        }
        int sortedIndex = candidates.indexOf(isolated);
        int selectedIndex = selectedCandidates.indexOf(isolated);
        String selectionReason = selectedIndex >= 0
                ? "selected"
                : findIsolationSelectionReason(isolated, selectedCandidates, limit);
        if (BedrockDebugLog.isEnabled()) {
            BedrockDebugLog.write("isolated selection pos=" + BedrockDebugLog.pos(isolated.pos())
                    + " sortedIndex=" + sortedIndex
                    + " selectedIndex=" + selectedIndex
                    + " limit=" + limit
                    + " selectedCount=" + selectedCandidates.size()
                    + " reason=" + selectionReason);
        }
    }

    private CandidateInfo findBestIsolatedCandidate(List<CandidateInfo> candidates) {
        CandidateInfo best = null;
        for (CandidateInfo candidate : candidates) {
            if (candidate.neighborTargetCount() != 0) {
                continue;
            }
            if (best == null
                    || candidate.priority() < best.priority()
                    || (candidate.priority() == best.priority()
                    && candidate.pos().asLong() < best.pos().asLong())) {
                best = candidate;
            }
        }
        return best;
    }

    private String findIsolationSelectionReason(CandidateInfo isolated, List<CandidateInfo> selectedCandidates, int limit) {
        if (limit <= 0) {
            return "limit_zero";
        }
        if (selectedCandidates.size() >= limit) {
            return "limit_reached";
        }
        for (CandidateInfo existing : selectedCandidates) {
            if (candidatesConflict(isolated, existing)) {
                return "conflict_with=" + BedrockDebugLog.pos(existing.pos());
            }
        }
        return "not_selected_unknown";
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
                || intersects(left.powerReservationPositions(), right.structuralPositions())) {
            return true;
        }
        if (left.placement() != null && right.placement() != null
                && sameTorchPlacement(left.placement(), right.placement())) {
            return false;
        }
        return isTorchPoweredBy(left.layout().getPistonPos(), right.placement())
                || isTorchPoweredBy(right.layout().getPistonPos(), left.placement());
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

    private PlacementSelection findPlacementSelection(BedrockMachineLayout layout, BlockPos bedrockPos) {
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
            return new PlacementSelection(placement, this.level.getBlockState(placement.getSupportPos()).is(Blocks.SLIME_BLOCK)
                    ? placement.getSupportPos()
                    : null);
        }
        placement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                this.level,
                layout.getPistonPos(),
                layout.getPistonOffset().getOpposite(),
                bedrockPos,
                layout.getPistonPos(),
                layout.getHeadPos()
        );
        return placement == null ? null : new PlacementSelection(placement, placement.getSupportPos());
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

    private record CandidateInfo(
            BlockPos pos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos,
            List<BlockPos> structuralPositions,
            List<BlockPos> powerReservationPositions,
            int priority,
            int neighborTargetCount
    ) {
    }

    private record PlacementSelection(BedrockTorchPlacement placement, BlockPos slimePos) {
    }

}
