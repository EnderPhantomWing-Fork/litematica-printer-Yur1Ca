package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BedrockController {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final String RETRY_COOLDOWN_KEY = "bedrock_retry";
    private static final String CLEANUP_RETRY_COOLDOWN_KEY = "cleanup_retry";
    private static final int SUBMIT_RETRY_COOLDOWN_TICKS = 6;
    private static final int FAILURE_RETRY_COOLDOWN_TICKS = 12;
    private static final int CLEANUP_LIMIT_PER_TICK = 48;
    private static final List<BedrockTarget> TARGETS = new ArrayList<>();
    private static final Set<BlockPos> CLEANUP_QUEUE = new LinkedHashSet<>();
    private static final Set<BlockPos> CONSERVATIVE_CLEANUP = new LinkedHashSet<>();
    private static final Map<BlockPos, BedrockMachineLayout> PREPARED_LAYOUTS = new HashMap<>();
    private static long nextAcceptTick = 0L;
    private static long nextExecuteTick = 0L;
    private static long lastProcessedTick = Long.MIN_VALUE;

    private BedrockController() {
    }

    public static void reset() {
        if (!TARGETS.isEmpty() || !CLEANUP_QUEUE.isEmpty()) {
            BedrockDebugLog.write("controller reset targets=" + TARGETS.size() + " cleanup=" + CLEANUP_QUEUE.size());
        }
        TARGETS.clear();
        CLEANUP_QUEUE.clear();
        CONSERVATIVE_CLEANUP.clear();
        PREPARED_LAYOUTS.clear();
        nextAcceptTick = 0L;
        nextExecuteTick = 0L;
        lastProcessedTick = Long.MIN_VALUE;
    }

    public static void tick() {
        ClientLevel level = CLIENT.level;
        if (level == null) {
            reset();
            return;
        }

        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now == lastProcessedTick) {
            return;
        }
        lastProcessedTick = now;
        PREPARED_LAYOUTS.clear();

        // 强力清理队列：死磕到底，变空气才移除
        processCleanupQueue();

        if (BedrockInventory.warningMessage() != null) {
            return;
        }

        int executeBudget = getExecuteBudget();
        int initialExecuteBudget = executeBudget;
        if (!TARGETS.isEmpty()) {
            BedrockDebugLog.write("controller tick targets=" + TARGETS.size()
                    + " active=" + countActiveTargets()
                    + " cleanup=" + CLEANUP_QUEUE.size()
                    + " budget=" + executeBudget
                    + " nextExecuteTick=" + nextExecuteTick
                    + " now=" + now);
        }
        Set<BedrockTarget> processedTargets = new LinkedHashSet<>();
        executeBudget = processTargets(level, executeBudget, true, processedTargets);
        executeBudget = processTargets(level, executeBudget, false, processedTargets);
        executeBudget = processTargets(level, executeBudget, true, processedTargets);
        
        if (executeBudget < initialExecuteBudget) {
            scheduleNextExecuteWindow();
        }
    }

    public static boolean canScanForTargets() {
        if (CLIENT.level == null) {
            return false;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        return now >= nextAcceptTick && countActiveTargets() < getActiveTargetCap();
    }

    public static boolean canAccept(BlockPos pos) {
        if (!canScanForTargets()) return false;
        if (isTargetOnRetryCooldown(pos)) return false;

        for (BedrockTarget target : TARGETS) {
            if (target.getBedrockPos().equals(pos)) return false;
            if (target.getPistonPos().equals(pos)) return false;
        }
        return true;
    }

    public static boolean prepareCandidate(BlockPos pos) {
        ClientLevel level = CLIENT.level;
        if (level == null || pos == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            return false;
        }
        if (!canAccept(pos)) {
            return false;
        }

        BlockPos key = pos.immutable();
        if (PREPARED_LAYOUTS.containsKey(key)) {
            return true;
        }

        BedrockMachineLayout layout = BedrockMachineLayout.find(level, pos);
        if (layout == null) {
            setRetryCooldown(pos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }

        BedrockTarget previewTarget = new BedrockTarget(pos, level, layout);
        if (previewTarget.getStatus() == BedrockTarget.Status.FAILED) {
            setRetryCooldown(pos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }
        if (previewTarget.getFirstOutOfRangeInteractionPos() != null) {
            setRetryCooldown(pos, SUBMIT_RETRY_COOLDOWN_TICKS);
            return false;
        }

        PREPARED_LAYOUTS.put(key, layout);
        return true;
    }

    public static boolean submit(BlockPos pos) {
        ClientLevel level = CLIENT.level;
        if (level == null || !BedrockTargetBlocks.isTargetBlock(level.getBlockState(pos))) {
            PREPARED_LAYOUTS.remove(pos);
            return false;
        }

        if (!canAccept(pos)) {
            PREPARED_LAYOUTS.remove(pos);
            return false;
        }

        BedrockMachineLayout preparedLayout = PREPARED_LAYOUTS.remove(pos);
        BedrockTarget target = preparedLayout != null
                ? new BedrockTarget(pos, level, preparedLayout)
                : new BedrockTarget(pos, level);
        if (target.getStatus() == BedrockTarget.Status.FAILED) {
            setRetryCooldown(pos, SUBMIT_RETRY_COOLDOWN_TICKS);
            BedrockDebugLog.write("submit failed bedrock=" + BedrockDebugLog.pos(pos) + " reason=target_failed_on_create");
            return false;
        }

        BlockPos pendingCleanupPos = findPendingCleanupConflict(target);
        if (pendingCleanupPos != null) {
            setRetryCooldown(pos, SUBMIT_RETRY_COOLDOWN_TICKS);
            BedrockDebugLog.write("submit rejected bedrock=" + BedrockDebugLog.pos(pos)
                    + " reason=pending_cleanup"
                    + " blockingPos=" + BedrockDebugLog.pos(pendingCleanupPos)
                    + " blockingState=" + BedrockDebugLog.describeState(level.getBlockState(pendingCleanupPos)));
            return false;
        }

        BedrockTarget conflict = findConflictTarget(target);
        if (conflict != null) {
            setRetryCooldown(pos, 2);
            BedrockDebugLog.write("submit rejected bedrock=" + BedrockDebugLog.pos(pos)
                    + " reason=machine_overlap"
                    + " conflictBedrock=" + BedrockDebugLog.pos(conflict.getBedrockPos())
                    + " torchSupport=" + BedrockDebugLog.pos(target.getTorchSupportPos())
                    + " torch=" + BedrockDebugLog.pos(target.getTorchPos())
                    + " piston=" + BedrockDebugLog.pos(target.getPistonPos()));
            return false;
        }

        if (target.getStatus() != BedrockTarget.Status.FAILED) {
            TARGETS.add(target);
            BedrockDebugLog.write("submit accepted bedrock=" + BedrockDebugLog.pos(pos)
                    + " piston=" + BedrockDebugLog.pos(target.getPistonPos())
                    + " torchSupport=" + BedrockDebugLog.pos(target.getTorchSupportPos())
                    + " torch=" + BedrockDebugLog.pos(target.getTorchPos())
                    + " slime=" + BedrockDebugLog.pos(target.getSlimePos())
                    + " conservativeSync=" + target.usesConservativeSync());
            updateAcceptThrottleAfterSubmit();
            return true;
        }
        return false;
    }

    private static void addToCleanup(BlockPos pos) {
        addToCleanup(pos, true);
    }

    private static void addToCleanup(BlockPos pos, boolean predictRemoval) {
        if (pos != null) {
            CLEANUP_QUEUE.add(pos);
            if (!predictRemoval) {
                CONSERVATIVE_CLEANUP.add(pos);
            }
        }
    }

    private static void processCleanupQueue() {
        if (CLEANUP_QUEUE.isEmpty()) return;

        reorderCleanupQueue();
        int limit = CLEANUP_LIMIT_PER_TICK;
        int count = 0;
        Iterator<BlockPos> iterator = CLEANUP_QUEUE.iterator();

        while (iterator.hasNext() && count < limit) {
            BlockPos pos = iterator.next();
            if (CLIENT.level == null) break;

            var state = CLIENT.level.getBlockState(pos);
            if (state.isAir()) {
                iterator.remove();
                CONSERVATIVE_CLEANUP.remove(pos);
                continue;
            }

            // Cleanup only touches machine residues to avoid touching user structures.
            if (!BedrockTargetBlocks.isCleanupResidue(state)) {
                iterator.remove();
                CONSERVATIVE_CLEANUP.remove(pos);
                continue;
            }

            if (isReservedByActiveTarget(pos)) {
                continue;
            }

            int retryDelay = getCleanupRetryDelay(state);
            if (!CooldownUtils.INSTANCE.isOnCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos)) {
                boolean predictRemoval = !CONSERVATIVE_CLEANUP.contains(pos);
                BedrockBreaker.breakBlock(pos, predictRemoval);
                CooldownUtils.INSTANCE.setCooldown(CLIENT.level, CLEANUP_RETRY_COOLDOWN_KEY, pos, retryDelay);
                BedrockDebugLog.write("cleanup retry pos=" + BedrockDebugLog.pos(pos)
                        + " state=" + BedrockDebugLog.describeState(state)
                        + " predictRemoval=" + predictRemoval
                        + " retryDelay=" + retryDelay);
                count++;
            }
        }
    }

    private static int getExecuteBudget() {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now < nextExecuteTick) {
            return 0;
        }
        return getConfiguredExecuteBudget();
    }

    private static BedrockTarget findConflictTarget(BedrockTarget candidate) {
        Set<BlockPos> candidateFootprint = candidate.getMachineFootprint();
        for (BedrockTarget existing : TARGETS) {
            Set<BlockPos> existingFootprint = existing.getMachineFootprint();
            for (BlockPos pos : candidateFootprint) {
                if (existingFootprint.contains(pos)) {
                    return existing;
                }
            }
        }
        return null;
    }

    private static BlockPos findPendingCleanupConflict(BedrockTarget candidate) {
        if (CLIENT.level == null) {
            return null;
        }
        for (BlockPos pos : getBlockingCleanupPositions(candidate)) {
            if (pos.equals(candidate.getBedrockPos())) {
                continue;
            }
            var state = CLIENT.level.getBlockState(pos);
            // 已经是空气，直接跳过（含清理队列中已被清理的条目）
            if (state.isAir()) {
                CLEANUP_QUEUE.remove(pos);
                CONSERVATIVE_CLEANUP.remove(pos);
                continue;
            }
            if (CLEANUP_QUEUE.contains(pos) && BedrockTargetBlocks.isCleanupResidue(state)) {
                return pos;
            }
            if (BedrockTargetBlocks.isCleanupResidue(state)) {
                if (!isReservedByActiveTarget(pos)) {
                    addToCleanup(pos, false);
                }
                return pos;
            }
        }
        return null;
    }

    private static Set<BlockPos> getBlockingCleanupPositions(BedrockTarget candidate) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        positions.add(candidate.getPistonPos());
        positions.add(candidate.getHeadPos());
        if (candidate.getTorchSupportPos() != null) {
            positions.add(candidate.getTorchSupportPos());
        }
        if (candidate.getTorchPos() != null) {
            positions.add(candidate.getTorchPos());
        }
        if (candidate.getSlimePos() != null) {
            positions.add(candidate.getSlimePos());
        }
        return positions;
    }

    private static int processTargets(ClientLevel level, int executeBudget, boolean priorityOnly, Set<BedrockTarget> processedTargets) {
        Iterator<BedrockTarget> iterator = TARGETS.iterator();
        while (iterator.hasNext()) {
            BedrockTarget target = iterator.next();
            if (target == null) {
                iterator.remove();
                continue;
            }
            if (processedTargets.contains(target)) {
                continue;
            }
            boolean fastLane = isFastLaneStatus(target.getStatus());
            if (priorityOnly != fastLane) {
                continue;
            }

            boolean allowInitialize = priorityOnly || executeBudget > 0;
            BedrockTarget.Status status = target.tick(priorityOnly || executeBudget > 0, allowInitialize);
            processedTargets.add(target);
            if (target.initializedThisTick()) {
                if (!priorityOnly) {
                    executeBudget--;
                    BedrockDebugLog.write("controller init consumed bedrock=" + BedrockDebugLog.pos(target.getBedrockPos())
                            + " remainingBudget=" + executeBudget);
                } else {
                    BedrockDebugLog.write("controller fastlane init bedrock=" + BedrockDebugLog.pos(target.getBedrockPos())
                            + " status=" + status);
                }
            }
            if (target.executedThisTick()) {
                if (priorityOnly) {
                    BedrockDebugLog.write("controller fastlane execute bedrock=" + BedrockDebugLog.pos(target.getBedrockPos())
                            + " status=" + status);
                } else {
                    executeBudget--;
                    BedrockDebugLog.write("controller execute consumed bedrock=" + BedrockDebugLog.pos(target.getBedrockPos())
                            + " remainingBudget=" + executeBudget);
                }
            }

            boolean retireOnSuccessfulRetracting = status == BedrockTarget.Status.RETRACTING
                    && !BedrockTargetBlocks.isTargetBlock(level.getBlockState(target.getBedrockPos()));
            if (status == BedrockTarget.Status.RETRACTED
                    || status == BedrockTarget.Status.FAILED
                    || status == BedrockTarget.Status.STUCK
                    || retireOnSuccessfulRetracting) {
                cleanupTarget(iterator, target, null);
            }
        }
        return executeBudget;
    }

    private static void cleanupTarget(Iterator<BedrockTarget> iterator, BedrockTarget target, String reason) {
        if (target.getStatus() == BedrockTarget.Status.FAILED || target.getStatus() == BedrockTarget.Status.STUCK) {
            setRetryCooldown(target.getBedrockPos(), FAILURE_RETRY_COOLDOWN_TICKS);
        } else if ("out_of_range".equals(reason)) {
            setRetryCooldown(target.getBedrockPos(), SUBMIT_RETRY_COOLDOWN_TICKS);
        }
        BedrockDebugLog.write("controller cleanup start bedrock=" + BedrockDebugLog.pos(target.getBedrockPos())
                + " status=" + target.getStatus()
                + " cleanupCount=" + target.getCleanupPositions().size()
                + (reason == null ? "" : " reason=" + reason));
        iterator.remove();
        for (BlockPos tempPos : target.getCleanupPositions()) {
            cleanupBlockOrQueue(tempPos, false);
        }
    }

    private static int countActiveTargets() {
        int count = 0;
        for (BedrockTarget target : TARGETS) {
            if (target != null && countsTowardsActiveCap(target.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private static int getActiveTargetCap() {
        int executeBudget = getConfiguredExecuteBudget();
        return Math.max(4, Math.min(10, executeBudget + 2));
    }

    private static int getConfiguredExecuteBudget() {
        int executeBudget = Configs.Bedrock.BEDROCK_BLOCKS_PER_TICK.getIntegerValue();
        return executeBudget <= 0 ? 64 : executeBudget;
    }

    private static void updateAcceptThrottleAfterSubmit() {
        int activeTargets = countActiveTargets();
        int pauseThreshold = getAcceptPauseThreshold(activeTargets);
        if (activeTargets < pauseThreshold) {
            return;
        }

        long nextTick = ClientPlayerTickManager.getCurrentHandlerTime() + 1L;
        if (nextAcceptTick < nextTick) {
            nextAcceptTick = nextTick;
        }
    }

    private static int getAcceptPauseThreshold(int activeTargets) {
        int hardCap = getActiveTargetCap();
        if (hardCap <= 2) {
            return hardCap;
        }

        int executeBudget = getConfiguredExecuteBudget();
        int earlyWaveCap = Math.min(hardCap, Math.max(2, Math.min(4, executeBudget)));
        int mediumWaveCap = Math.min(hardCap, Math.max(earlyWaveCap, Math.min(6, Math.max(3, executeBudget))));

        if (activeTargets <= earlyWaveCap) {
            return earlyWaveCap;
        }
        if (activeTargets <= mediumWaveCap) {
            return mediumWaveCap;
        }
        return hardCap;
    }

    private static boolean countsTowardsActiveCap(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.UNINITIALIZED
                || status == BedrockTarget.Status.UNEXTENDED_WITH_POWER_SOURCE
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE
                || status == BedrockTarget.Status.EXTENDED;
    }

    private static boolean isFastLaneStatus(BedrockTarget.Status status) {
        return status == BedrockTarget.Status.EXTENDED
                || status == BedrockTarget.Status.UNEXTENDED_WITHOUT_POWER_SOURCE;
    }

    private static void cleanupBlockOrQueue(BlockPos pos, boolean predictRemoval) {
        if (pos == null) {
            return;
        }

        addToCleanup(pos, predictRemoval);
        if (CLIENT.level == null) {
            return;
        }
        if (isReservedByActiveTarget(pos)) {
            BedrockDebugLog.write("cleanup deferred pos=" + BedrockDebugLog.pos(pos) + " reason=reserved_by_active_target");
            return;
        }
        if (BedrockTargetBlocks.isCleanupResidue(CLIENT.level.getBlockState(pos))) {
            BedrockBreaker.breakBlock(pos, predictRemoval);
        }
    }

    private static void reorderCleanupQueue() {
        if (CLEANUP_QUEUE.size() < 2 || CLIENT.level == null) {
            return;
        }
        List<BlockPos> ordered = new ArrayList<>(CLEANUP_QUEUE);
        ordered.sort((left, right) -> Integer.compare(
                getCleanupPriority(CLIENT.level.getBlockState(left)),
                getCleanupPriority(CLIENT.level.getBlockState(right))
        ));
        CLEANUP_QUEUE.clear();
        CLEANUP_QUEUE.addAll(ordered);
    }

    private static int getCleanupPriority(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_TORCH)
                || state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH)) {
            return 0;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD)
                || state.is(net.minecraft.world.level.block.Blocks.PISTON)) {
            return 1;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return 2;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return 3;
        }
        return 4;
    }

    private static int getCleanupRetryDelay(net.minecraft.world.level.block.state.BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_TORCH)
                || state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WALL_TORCH)) {
            return 3;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.PISTON_HEAD)
                || state.is(net.minecraft.world.level.block.Blocks.PISTON)) {
            return 4;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) {
            return 8;
        }
        if (state.is(net.minecraft.world.level.block.Blocks.SLIME_BLOCK)) {
            return 10;
        }
        return 6;
    }

    private static boolean isReservedByActiveTarget(BlockPos pos) {
        for (BedrockTarget target : TARGETS) {
            if (target.getReservedPositions().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    // 检查某位置是否被"其他"机器占用（排除 self 自身）
    static boolean isPositionReservedByOtherTarget(BlockPos pos, BedrockTarget self) {
        for (BedrockTarget target : TARGETS) {
            if (target == self) continue;
            if (target.getReservedPositions().contains(pos)) return true;
        }
        return false;
    }

    private static void scheduleNextExecuteWindow() {
        int interval = Math.max(0, Configs.Bedrock.BEDROCK_INTERVAL.getIntegerValue());
        if (interval <= 0) {
            return;
        }
        nextExecuteTick = ClientPlayerTickManager.getCurrentHandlerTime() + interval;
        BedrockDebugLog.write("controller schedule interval=" + interval + " nextExecuteTick=" + nextExecuteTick);
    }

    private static boolean isTargetOnRetryCooldown(BlockPos pos) {
        return CLIENT.level != null && CooldownUtils.INSTANCE.isOnCooldown(CLIENT.level, RETRY_COOLDOWN_KEY, pos);
    }

    private static void setRetryCooldown(BlockPos pos, int ticks) {
        if (CLIENT.level != null && pos != null && ticks > 0) {
            CooldownUtils.INSTANCE.setCooldown(CLIENT.level, RETRY_COOLDOWN_KEY, pos, ticks);
        }
    }
}
