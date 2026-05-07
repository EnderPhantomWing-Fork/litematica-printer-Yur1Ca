package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class BedrockMachineLayout {
    private static final Direction[] SEARCH_ORDER = {
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP,
            Direction.DOWN
    };

    private final BlockPos bedrockPos;
    private final Direction pistonOffset;
    private final BlockPos pistonPos;
    private final BlockPos headPos;

    private BedrockMachineLayout(BlockPos bedrockPos, Direction pistonOffset) {
        this.bedrockPos = bedrockPos;
        this.pistonOffset = pistonOffset;
        this.pistonPos = bedrockPos.relative(pistonOffset);
        this.headPos = this.pistonPos.relative(pistonOffset);
    }

    public static BedrockMachineLayout find(ClientLevel level, BlockPos bedrockPos) {
        if (level == null || bedrockPos == null) {
            return null;
        }
        return findLayout(level, bedrockPos);
    }

    private static BedrockMachineLayout findLayout(ClientLevel level, BlockPos bedrockPos) {
        ScoredLayout bestLayout = null;

        for (Direction direction : SEARCH_ORDER) {
            BedrockMachineLayout layout = new BedrockMachineLayout(bedrockPos, direction);
            if (!BedrockEnvironment.hasRoomForPiston(level, layout.getPistonPos(), layout.getPistonOffset())) {
                continue;
            }
            bestLayout = considerPlacement(level, bedrockPos, layout,
                    BedrockEnvironment.findTorchPlacement(level, layout.getPistonPos(), layout.getPistonOffset().getOpposite(), bedrockPos, layout.getPistonPos(), layout.getHeadPos()),
                    bestLayout);
            bestLayout = considerPlacement(level, bedrockPos, layout,
                    BedrockEnvironment.findPossibleSlimeTorchPlacement(level, layout.getPistonPos(), layout.getPistonOffset().getOpposite(), bedrockPos, layout.getPistonPos(), layout.getHeadPos()),
                    bestLayout);
        }
        return bestLayout == null ? null : bestLayout.layout();
    }

    private static ScoredLayout considerPlacement(ClientLevel level,
                                                  BlockPos bedrockPos,
                                                  BedrockMachineLayout layout,
                                                  BedrockTorchPlacement torchPlacement,
                                                  ScoredLayout currentBest) {
        if (torchPlacement == null) {
            return currentBest;
        }
        if (!BedrockEnvironment.arePositionsInteractable(torchPlacement.getSupportPos())) {
            return currentBest;
        }
        BedrockEnvironment.PlacementInteraction placementInteraction = BedrockEnvironment.findPlacementInteraction(
                level,
                layout.getPistonPos(),
                bedrockPos,
                torchPlacement.getSupportPos(),
                torchPlacement.getTorchPos()
        );
        if (placementInteraction == null) {
            return currentBest;
        }
        int score = scoreLayout(level, layout, torchPlacement, placementInteraction);
        if (currentBest == null || score < currentBest.score()) {
            return new ScoredLayout(layout, score);
        }
        return currentBest;
    }

    public static boolean shouldDeferUntilExposed(ClientLevel level, BlockPos bedrockPos) {
        if (level == null || bedrockPos == null) {
            return false;
        }
        if (find(level, bedrockPos) != null) {
            return false;
        }
        boolean sawBlockedLayout = false;
        for (Direction direction : SEARCH_ORDER) {
            BedrockMachineLayout layout = new BedrockMachineLayout(bedrockPos, direction);
            if (isBlockingTarget(level, bedrockPos, layout.getPistonPos())
                    || isBlockingTarget(level, bedrockPos, layout.getHeadPos())) {
                sawBlockedLayout = true;
                continue;
            }
            if (hasBlockingTorchPlacement(level, bedrockPos, layout)) {
                sawBlockedLayout = true;
                continue;
            }
            return false;
        }
        return sawBlockedLayout;
    }

    public BlockPos getBedrockPos() {
        return this.bedrockPos;
    }

    public Direction getPistonOffset() {
        return this.pistonOffset;
    }

    public BlockPos getPistonPos() {
        return this.pistonPos;
    }

    public BlockPos getHeadPos() {
        return this.headPos;
    }

    public Direction getPistonPlacementFace() {
        return this.pistonOffset;
    }

    public Direction getPrimingFacing() {
        return this.pistonOffset;
    }

    public Direction getExecuteFacing() {
        return this.pistonOffset.getOpposite();
    }

    private static boolean isBlockingTarget(ClientLevel level, BlockPos bedrockPos, BlockPos pos) {
        return pos != null
                && !pos.equals(bedrockPos)
                && !level.isOutsideBuildHeight(pos)
                && BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos));
    }

    private static boolean hasBlockingTorchPlacement(ClientLevel level, BlockPos bedrockPos, BedrockMachineLayout layout) {
        BlockPos centerPos = layout.getPistonPos();
        Direction excludedAxis = layout.getPistonOffset().getOpposite();

        for (Direction direction : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            if (direction == excludedAxis) {
                continue;
            }

            BlockPos topSupportPos = centerPos.relative(direction);
            BlockPos topTorchPos = topSupportPos.above();
            if (isBlockingTarget(level, bedrockPos, topSupportPos) || isBlockingTarget(level, bedrockPos, topTorchPos)) {
                return true;
            }

            BlockPos wallTorchPos = centerPos.relative(direction);
            if (isBlockingTarget(level, bedrockPos, wallTorchPos)) {
                return true;
            }
        }

        return false;
    }

    private static int scoreLayout(ClientLevel level, BedrockMachineLayout layout, BedrockTorchPlacement torchPlacement, BedrockEnvironment.PlacementInteraction placementInteraction) {
        boolean slimePlacement = level != null
                && torchPlacement != null
                && BedrockEnvironment.isSlimePlacementUsable(level, torchPlacement)
                && !BedrockEnvironment.isTorchPlacementUsable(level, torchPlacement);
        int score = directionPenalty(layout.getPistonOffset());
        score += BedrockEnvironment.scoreInteractionPosition(layout.getPistonPos()) * 4;
        score += BedrockEnvironment.scoreInteractionPosition(layout.getHeadPos());
        score += BedrockEnvironment.scoreTorchPlacement(layout.getPistonPos(), torchPlacement, 0, slimePlacement);
        score += BedrockEnvironment.scorePlacementInteraction(layout.getPistonPos(), placementInteraction);
        return score;
    }

    private static int directionPenalty(Direction offset) {
        if (offset == null) {
            return Integer.MAX_VALUE / 4;
        }
        if (offset.getAxis().isHorizontal()) {
            return 0;
        }
        if (offset == Direction.UP) {
            return 180;
        }
        return 360;
    }

    private record ScoredLayout(BedrockMachineLayout layout, int score) {
    }

}
