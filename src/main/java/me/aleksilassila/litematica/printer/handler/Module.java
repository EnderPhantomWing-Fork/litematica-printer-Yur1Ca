package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.handler.scan.DirtyRegionTracker;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public abstract class Module extends ConfigUtils {
    private static final int LAZY_DIRTY_FULL_SCAN_THRESHOLD = 2;

    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> playerInteractionBox;
    private final InteractionBoxTracker interactionBoxTracker;
    private final GuiBlockInfoBuffer guiBlockInfoBuffer = new GuiBlockInfoBuffer();
    @Getter
    private final String id;
    @Getter
    @Nullable
    private final PrintModeType printMode;
    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;
    @Getter
    @Nullable
    private final ConfigOptionList selectionType;
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);
    private boolean iterationConsumedEffectiveExecution = true;
    @Getter
    private ScanState scanState = ScanState.FULL;
    @Getter
    private int pendingDirtyRegionCount;
    private int idleScanTicks;
    @Nullable
    private PrinterBox lastScanSourceBox;
    private long lastDirtyVersion;
    private final ArrayDeque<PrinterBox> dirtyScanQueue = new ArrayDeque<>();
    @Nullable
    private PrinterBox activeDirtyScanBox;
    private boolean currentIterationDidWork;
    private boolean currentIterationFoundCandidate;

    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;

    private long lastTickTime = -1L;

    protected Module(String id, @Nullable PrintModeType printMode, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.printMode = printMode;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.interactionBoxTracker = new InteractionBoxTracker(useBox);
        this.playerInteractionBox = this.interactionBoxTracker.getBoxReference();
        this.updateVariables();
    }

    protected void updateVariables() {
        this.updateVariables(TickContext.capture());
    }

    public void tick() {
        this.tick(TickContext.capture());
    }

    public void tick(TickContext context) {
        this.guiBlockInfoBuffer.tickCache();
        if (this.shouldSkipByTickInterval(context)) {
            return;
        }
        if (!isEnable()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        this.updateVariables(context);
        if (!this.hasRequiredClientState()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        ScanCache.INSTANCE.beginTick(this.level, SchematicWorldHandler.getSchematicWorld(), context.gameTime);
        this.updatePlayerInteractionBox();
        this.preprocess(); // 运行前处理的事情
        if (!this.isConfigAllowExecute()) {
            this.resetScanRuntime();
            this.resetPlayerTracking();
            return;
        }
        boolean interrupt = this.runIterationIfNeeded();
        if (!interrupt) {
            this.resetPlayerTracking();
        }
    }

    protected void updateVariables(TickContext context) {
        this.mc = context.mc;
        this.level = context.level;
        this.player = context.player;
        this.connection = context.connection;
        this.gameMode = context.gameMode;
        this.gameType = context.gameType;
        this.hitResult = context.hitResult;
        this.blockHitResult = context.blockHitResult;
    }

    private boolean shouldSkipByTickInterval(TickContext context) {
        int tickInterval = this.getTickInterval();
        if (tickInterval <= 0) {
            return false;
        }
        long currentTickTime = context.gameTime;
        if (this.lastTickTime != -1L && currentTickTime - this.lastTickTime < tickInterval) {
            return true;
        }
        this.lastTickTime = currentTickTime;
        return false;
    }

    private boolean hasRequiredClientState() {
        return this.mc != null
                && this.level != null
                && this.player != null
                && this.connection != null
                && this.gameMode != null
                && this.gameType != null;
    }

    private void resetPlayerTracking() {
        this.interactionBoxTracker.resetPlayerTracking();
    }

    private void updatePlayerInteractionBox() {
        this.interactionBoxTracker.update(this.player);
    }

    private boolean runIterationIfNeeded() {
        if (this.playerInteractionBox == null || !this.canExecute()) {
            return false;
        }
        PrinterBox playerInteractionBox = this.playerInteractionBox.get();
        if (playerInteractionBox == null || !this.canIterate()) {
            return false;
        }
        PrinterBox scanSourceBox = this.getScanSourceBox(playerInteractionBox);
        if (scanSourceBox == null) {
            this.lastScanSourceBox = null;
            return false;
        }
        this.updateScanSource(scanSourceBox);
        if (!this.isLazyScanEnabled()) {
            this.scanState = ScanState.FULL;
            this.clearDirtyScanQueue();
            return this.runFullIteration(playerInteractionBox);
        }
        return switch (this.scanState) {
            case FULL -> this.runFullIteration(playerInteractionBox);
            case PARTIAL -> this.runPartialIteration(playerInteractionBox);
            case LAZY -> this.runLazyIteration(playerInteractionBox);
        };
    }

    private void updateScanSource(PrinterBox scanSourceBox) {
        if (scanSourceBox.equals(this.lastScanSourceBox)) {
            return;
        }
        this.lastScanSourceBox = scanSourceBox;
        this.scanState = ScanState.FULL;
        this.idleScanTicks = 0;
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private boolean runFullIteration(PrinterBox playerInteractionBox) {
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        this.updateFullScanIdleState(interrupt);
        return interrupt;
    }

    private boolean runLazyIteration(PrinterBox playerInteractionBox) {
        this.refreshDirtyScanQueue(playerInteractionBox);
        if (this.scanState == ScanState.LAZY) {
            return this.runLazyProbeIteration(playerInteractionBox);
        }
        if (this.scanState == ScanState.FULL) {
            return this.runFullIteration(playerInteractionBox);
        }
        return this.runPartialIteration(playerInteractionBox);
    }

    private boolean runLazyProbeIteration(PrinterBox playerInteractionBox) {
        this.pendingDirtyRegionCount = 0;
        boolean interrupt = this.runIterationLoop(playerInteractionBox);
        if (this.currentIterationDidWork || this.currentIterationFoundCandidate) {
            this.scanState = ScanState.FULL;
            this.idleScanTicks = 0;
            return interrupt;
        }
        this.scanState = ScanState.LAZY;
        return true;
    }

    private boolean runPartialIteration(PrinterBox playerInteractionBox) {
        if (this.activeDirtyScanBox == null) {
            if (this.dirtyScanQueue.isEmpty()) {
                this.refreshDirtyScanQueue(playerInteractionBox);
                if (this.scanState == ScanState.LAZY) {
                    return false;
                }
                if (this.scanState == ScanState.FULL) {
                    return this.runFullIteration(playerInteractionBox);
                }
            }
            this.activeDirtyScanBox = this.dirtyScanQueue.pollFirst();
        }

        if (this.activeDirtyScanBox == null) {
            this.scanState = ScanState.LAZY;
            this.pendingDirtyRegionCount = 0;
            return false;
        }

        PrinterBox boundedDirtyBox = intersect(playerInteractionBox, this.activeDirtyScanBox);
        if (boundedDirtyBox == null || this.getScanSourceBox(boundedDirtyBox) == null) {
            this.activeDirtyScanBox = null;
            this.updatePartialScanState(false);
            return this.hasPendingPartialScan();
        }

        boolean interrupt = this.runIterationLoop(boundedDirtyBox);
        if (!interrupt) {
            this.activeDirtyScanBox = null;
        }
        this.updatePartialScanState(interrupt);
        return interrupt || this.hasPendingPartialScan();
    }

    private void refreshDirtyScanQueue(PrinterBox playerInteractionBox) {
        DirtyRegionTracker.DirtySnapshot snapshot = DirtyRegionTracker.INSTANCE.snapshotAfter(this.lastDirtyVersion, playerInteractionBox);
        this.lastDirtyVersion = snapshot.version();
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;

        List<PrinterBox> dirtyBoxes = new ArrayList<>();
        for (PrinterBox dirtyBox : snapshot.boxes()) {
            PrinterBox boundedDirtyBox = intersect(playerInteractionBox, dirtyBox);
            if (boundedDirtyBox != null && this.getScanSourceBox(boundedDirtyBox) != null) {
                dirtyBoxes.add(boundedDirtyBox);
            }
        }
        dirtyBoxes.sort(Comparator.comparingDouble(this::distanceToPlayerSqr));
        this.dirtyScanQueue.addAll(dirtyBoxes);

        this.pendingDirtyRegionCount = this.dirtyScanQueue.size();
        if (this.dirtyScanQueue.isEmpty()) {
            this.scanState = ScanState.LAZY;
            return;
        }

        int fullThreshold = LAZY_DIRTY_FULL_SCAN_THRESHOLD;
        if (fullThreshold <= 0 || this.dirtyScanQueue.size() >= fullThreshold) {
            this.scanState = ScanState.FULL;
            this.clearDirtyScanQueue();
            return;
        }
        this.scanState = ScanState.PARTIAL;
    }

    private void updateFullScanIdleState(boolean interrupt) {
        if (interrupt) {
            this.idleScanTicks = 0;
            return;
        }
        if (this.currentIterationDidWork || this.currentIterationFoundCandidate) {
            this.idleScanTicks = 0;
            return;
        }
        int lazyThreshold = Configs.Core.LAZY_ENTER_TICKS.getIntegerValue();
        if (lazyThreshold <= 0) {
            return;
        }
        if (++this.idleScanTicks >= lazyThreshold) {
            this.scanState = ScanState.LAZY;
            this.idleScanTicks = 0;
            this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
            this.clearDirtyScanQueue();
        }
    }

    private void updatePartialScanState(boolean interrupt) {
        this.pendingDirtyRegionCount = this.dirtyScanQueue.size() + (this.activeDirtyScanBox == null ? 0 : 1);
        if (interrupt) {
            return;
        }
        if (!this.hasPendingPartialScan()) {
            this.scanState = ScanState.LAZY;
            this.idleScanTicks = 0;
            this.pendingDirtyRegionCount = 0;
        }
    }

    private boolean hasPendingPartialScan() {
        return this.activeDirtyScanBox != null || !this.dirtyScanQueue.isEmpty();
    }

    private boolean isLazyScanEnabled() {
        return Configs.Core.LAZY_ENTER_TICKS.getIntegerValue() > 0;
    }

    private void clearDirtyScanQueue() {
        this.dirtyScanQueue.clear();
        this.activeDirtyScanBox = null;
        this.pendingDirtyRegionCount = 0;
    }

    private void resetScanRuntime() {
        this.scanState = ScanState.FULL;
        this.idleScanTicks = 0;
        this.lastScanSourceBox = null;
        this.lastDirtyVersion = DirtyRegionTracker.INSTANCE.currentVersion();
        this.clearDirtyScanQueue();
    }

    private double distanceToPlayerSqr(PrinterBox box) {
        if (this.player == null) {
            return 0.0D;
        }
        double dx = axisDistance(this.player.getX(), box.minX, box.maxX);
        double dy = axisDistance(this.player.getEyeY(), box.minY, box.maxY);
        double dz = axisDistance(this.player.getZ(), box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double value, int min, int max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    private boolean runIterationLoop(PrinterBox playerInteractionBox) {
        int maxEffectiveExec = this.getMaxEffectiveExecutionsPerTick();
        int scanGuardLimit = this.getScanGuardLimit();
        int totalIterCount = 0;
        int effectiveExecCount = 0;
        boolean interrupt = false;
        boolean trackGuiBlockInfo = this.shouldTrackGuiBlockInfo();
        this.skipIteration.set(false);
        this.currentIterationDidWork = false;
        this.currentIterationFoundCandidate = false;
        this.guiBlockInfoBuffer.resetForTracking(trackGuiBlockInfo);

        Iterable<BlockPos> iterationPositions = this.getIterationPositions(playerInteractionBox);
        for (BlockPos pos : iterationPositions) {
            if (scanGuardLimit > 0 && totalIterCount++ >= scanGuardLimit) {
                interrupt = true;
                break;
            }
            if (this.skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                interrupt = true;
                break;
            }
            if (pos == null) {
                interrupt = true;
                break;
            }
            GuiBlockInfo gui = this.createGuiBlockInfo(trackGuiBlockInfo, pos);
            if (this.canReachIterationPosition(pos)) {
                if (gui != null) {
                    gui.interacted = true;
                }
            } else {
                if (gui != null) {
                    gui.interacted = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (isSchematicBlockHandler()) {
                if (!LitematicaUtils.isSchematicBlock(pos)) {
                    this.guiBlockInfoBuffer.add(gui);
                    continue;
                }
            }
            if (!this.isInSelectionRange(pos)) {
                if (gui != null) {
                    gui.posInSelectionRange = false;
                }
                this.guiBlockInfoBuffer.add(gui);
                continue;
            }
            if (gui != null) {
                gui.posInSelectionRange = true;
            }
            if (this.canIterationBlockPos(pos)) {
                this.currentIterationFoundCandidate = true;
                if (isBlockPosOnCooldown(pos)) {
                    this.guiBlockInfoBuffer.add(gui);
                    continue;
                }
                this.iterationConsumedEffectiveExecution = true;
                this.executeIteration(pos, this.skipIteration);
                if (gui != null) {
                    gui.execute = true;
                }
                boolean consumedEffectiveExecution = this.iterationConsumedEffectiveExecution;
                if (this.skipIteration.get()
                        || maxEffectiveExec > 0 && consumedEffectiveExecution && ++effectiveExecCount >= maxEffectiveExec) {
                    interrupt = true;
                }
                if (consumedEffectiveExecution) {
                    this.currentIterationDidWork = true;
                }
            }
            this.guiBlockInfoBuffer.add(gui);
            if (interrupt) {
                break;
            }
        }
        stopIteration(interrupt);
        return interrupt;
    }

    protected void stopIteration(boolean interrupt) {
    }

    protected boolean isSchematicBlockHandler() {
        return false;
    }

    protected boolean requiresSelection1ModeRangeCheck() {
        return true;
    }

    protected boolean shouldTrackGuiBlockInfo() {
        return false;
    }

    @Nullable
    private GuiBlockInfo createGuiBlockInfo(boolean enabled, BlockPos pos) {
        if (!enabled) {
            return null;
        }
        if (isSchematicBlockHandler()) {
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            return new GuiBlockInfo(level, schematic, pos);
        }
        return new GuiBlockInfo(level, null, pos);
    }

    @Nullable
    public GuiBlockInfo getCurrentRenderGuiBlockInfo() {
        return this.guiBlockInfoBuffer.current();
    }

    @Nullable
    public GuiBlockInfo getGuiBlockInfo() {
        return this.guiBlockInfoBuffer.latest();
    }

    public void setGuiBlockInfo(@Nullable GuiBlockInfo guiBlockInfo) {
        this.guiBlockInfoBuffer.add(guiBlockInfo);
    }

    public int getGuiBlockInfoQueueSize() {
        return this.guiBlockInfoBuffer.size();
    }

    public int getRenderIndex() {
        return this.guiBlockInfoBuffer.renderIndex();
    }

    private boolean isConfigAllowExecute() {
        // 全局打印机功能未启用，直接禁止所有处理器执行
        if (!ConfigUtils.isEnable()) {
            return false;
        }
        // 处理器绑定了模式和配置，按当前游戏模式校验
        if (this.printMode != null && this.enableConfig != null) {
            WorkingModeType modeType = (WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue();
            return switch (modeType) {
                case SINGLE -> Configs.Core.WORK_MODE_TYPE.getOptionListValue().equals(this.printMode);
                case MULTI -> this.enableConfig.getBooleanValue();
            };
        }
        // 仅绑定了启用配置，直接校验配置是否启用
        if (this.enableConfig != null) {
            return this.enableConfig.getBooleanValue();
        }
        // 无任何配置绑定，默认允许执行（由全局配置控制）
        return true;
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxEffectiveExecutionsPerTick() {
        return -1;
    }

    protected int getScanGuardLimit() {
        return 65_536;
    }

    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        return playerInteractionBox;
    }

    protected Iterable<BlockPos> getFilteredIterationPositions(PrinterBox playerInteractionBox, Predicate<BlockPos> candidatePredicate) {
        return ScanCache.INSTANCE.rawIterable(
                this.id + "_raw",
                playerInteractionBox,
                this.player,
                this.getScanGuardLimit(),
                candidatePredicate
        );
    }

    protected Iterable<BlockPos> getCachedFilteredIterationPositions(PrinterBox playerInteractionBox, ScanIntent intent, Predicate<BlockPos> candidatePredicate) {
        PrinterBox scanSourceBox = this.getScanSourceBox(playerInteractionBox);
        if (scanSourceBox == null) {
            return java.util.List.of();
        }
        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        return ScanCache.INSTANCE.iterable(
                this.id,
                scanSourceBox,
                this.level,
                SchematicWorldHandler.getSchematicWorld(),
                this.player,
                this.getScanGuardLimit(),
                intent,
                candidatePredicate,
                pos -> this.canReachIterationPosition(pos) && selectionPredicate.test(pos)
        );
    }

    @Nullable
    protected PrinterBox getScanSourceBox(PrinterBox playerInteractionBox) {
        if (playerInteractionBox == null) {
            return null;
        }
        if (isSchematicBlockHandler() || !requiresSelection1ModeRangeCheck()) {
            return playerInteractionBox;
        }
        PrinterBox selectionBox = LitematicaUtils.createSelection1BoundingBox();
        if (selectionBox == null) {
            return null;
        }
        return intersect(playerInteractionBox, selectionBox);
    }

    @Nullable
    private static PrinterBox intersect(PrinterBox first, PrinterBox second) {
        int minX = Math.max(first.minX, second.minX);
        int minY = Math.max(first.minY, second.minY);
        int minZ = Math.max(first.minZ, second.minZ);
        int maxX = Math.min(first.maxX, second.maxX);
        int maxY = Math.min(first.maxY, second.maxY);
        int maxZ = Math.min(first.maxZ, second.maxZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    protected boolean canReachIterationPosition(BlockPos pos) {
        return ConfigUtils.canInteracted(pos);
    }

    protected boolean isInSelectionRange(BlockPos pos) {
        if (!isSchematicBlockHandler()
                && requiresSelection1ModeRangeCheck()
                && !LitematicaUtils.isWithinSelection1ModeRange(pos)) {
            return false;
        }
        return selectionType == null || ConfigUtils.isPositionInSelectionRange(player, pos, selectionType);
    }

    protected Predicate<BlockPos> createSelectionRangePredicate() {
        Predicate<BlockPos> selection1Predicate = isSchematicBlockHandler() || !requiresSelection1ModeRangeCheck()
                ? pos -> true
                : LitematicaUtils.createSelection1RangePredicate();
        Predicate<BlockPos> configuredSelectionPredicate = this.createConfiguredSelectionRangePredicate();
        return pos -> selection1Predicate.test(pos) && configuredSelectionPredicate.test(pos);
    }

    private Predicate<BlockPos> createConfiguredSelectionRangePredicate() {
        if (this.selectionType == null) {
            return pos -> true;
        }
        if (!(this.selectionType.getOptionListValue() instanceof SelectionType selectionType)) {
            return pos -> false;
        }
        return switch (selectionType) {
            case LITEMATICA_SELECTION -> pos -> true;
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils::isPositionWithinRange;
            case LITEMATICA_SELECTION_BELOW_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.floor(this.player.getY());
                yield pos -> pos.getY() <= playerY;
            }
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> {
                if (this.player == null) {
                    yield pos -> false;
                }
                int playerY = (int) Math.ceil(this.player.getY());
                yield pos -> pos.getY() >= playerY;
            }
        };
    }

    public boolean canIterationBlockPos(BlockPos pos) {
        return true;
    }

    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    protected final void setIterationConsumedEffectiveExecution(boolean consumed) {
        this.iterationConsumedEffectiveExecution = consumed;
    }

    public boolean isBlockPosOnCooldown(@Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId(), pos);
    }

    public boolean isBlockPosOnCooldown(String name, @Nullable BlockPos pos) {
        if (this.level == null || pos == null) return true;
        return CooldownUtils.INSTANCE.isOnCooldown(this.level, this.getId() + "_" + name, pos);
    }

    public void setBlockPosCooldown(@Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId(), pos, cooldownTicks);
    }

    public void setBlockPosCooldown(String name, @Nullable BlockPos pos, int cooldownTicks) {
        if (this.level == null || pos == null || cooldownTicks < 1) return;
        CooldownUtils.INSTANCE.setCooldown(this.level, this.getId() + "_" + name, pos, cooldownTicks);
    }

    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return getPlayerOrderedByNearest()[0].getOpposite();
    }
}
