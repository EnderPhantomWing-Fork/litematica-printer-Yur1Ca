package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.mods.ModLoadUtils;
import me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST;
import static fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST;
import static fi.dy.masa.tweakeroo.tweaks.PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION;

@SuppressWarnings({"DataFlowIssue", "BooleanMethodIsAlwaysInverted"})
@Environment(EnvType.CLIENT)
public class InteractionUtils {
    public static final Minecraft client = Minecraft.getInstance();
    public static final InteractionUtils INSTANCE = new InteractionUtils();

    private final Queue<BlockPos> breakQueue = new LinkedList<>();
    private final Map<BlockPos, Integer> recentlyBroken = new HashMap<>();
    private final Map<BlockPos, Integer> pendingBroken = new HashMap<>();
    private BlockPos breakPos;
    private boolean forceDelayedDestroy;
    private int externalDestroyLockTicks;

    private InteractionUtils() {
    }

    public static boolean canBreakBlock(BlockPos pos) {
        ClientLevel world = client.level;
        LocalPlayer player = client.player;
        if (world == null || player == null) return false;
        BlockState currentState = world.getBlockState(pos);
        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && currentState.getBlock().defaultDestroyTime() < 0) {
            return false;
        }
        return !currentState.isAir() &&
                !currentState.is(Blocks.AIR) &&
                !currentState.is(Blocks.CAVE_AIR) &&
                !currentState.is(Blocks.VOID_AIR) &&
                !(currentState.getBlock() instanceof LiquidBlock) &&
                !player.blockActionRestricted(client.level, pos, client.gameMode.getPlayerMode());
    }

    public static boolean breakRestriction(BlockState blockState) {
        if (Configs.Break.BREAK_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            if (!ModLoadUtils.isTweakerooLoaded()) return true;
            UsageRestriction.ListType listType = BLOCK_TYPE_BREAK_RESTRICTION.getListType();
            if (listType == UsageRestriction.ListType.BLACKLIST) {
                return BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else if (listType == UsageRestriction.ListType.WHITELIST) {
                return BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings().stream()
                        .anyMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        } else {
            IConfigOptionListEntry optionListValue = Configs.Break.BREAK_LIMIT.getOptionListValue();
            if (optionListValue == UsageRestriction.ListType.BLACKLIST) {
                return Configs.Break.BREAK_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else if (optionListValue == UsageRestriction.ListType.WHITELIST) {
                return Configs.Break.BREAK_WHITELIST.getStrings().stream()
                        .anyMatch(string -> FilterUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        }
    }

    public static boolean trySwitchToEffectiveTool(BlockPos pos, BlockState blockState) {
        if (pos == null || blockState == null || blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            return false;
        }
        LocalPlayer player = client.player;
        if (Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()) {
            return player != null && InventoryUtils.switchToBestTool(player, blockState);
        }
        if (ModLoadUtils.isTweakerooLoaded() && TweakerooUtils.isToolSwitchEnabled()) {
            TweakerooUtils.trySwitchToEffectiveTool(pos);
            return true;
        }
        return false;
    }

    public static boolean isToolAllowedByDurabilityProtection(ItemStack stack) {
        return !TweakerooUtils.isToolTooDamagedForBreaking(stack);
    }

    public static boolean protectCurrentToolBeforeBreak() {
        LocalPlayer player = client.player;
        if (player == null || player.getAbilities().instabuild) {
            return true;
        }
        if (isToolAllowedByDurabilityProtection(player.getMainHandItem())) {
            return true;
        }
        TweakerooUtils.trySwapCurrentToolIfNearlyBroken();
        return isToolAllowedByDurabilityProtection(player.getMainHandItem());
    }

    public void add(BlockPos pos) {
        if (pos == null) return;
        breakQueue.add(pos);
    }

    public void add(SchematicBlockContext ctx) {
        if (ctx == null) return;
        this.add(ctx.blockPos);
    }

    private void tickRecentlyBroken() {
        tickBreakMarkerMap(this.recentlyBroken);
        tickBreakMarkerMap(this.pendingBroken);
    }

    private void tickBreakMarkerMap(Map<BlockPos, Integer> markerMap) {
        if (markerMap.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> iterator = markerMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            int remainingTicks = entry.getValue() - 1;
            if (remainingTicks <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remainingTicks);
            }
        }
    }

    public void preprocess() {
        this.tickRecentlyBroken();
        if (this.externalDestroyLockTicks > 0) {
            this.externalDestroyLockTicks--;
        }
        if (!ConfigUtils.isEnable()) {
            if (!breakQueue.isEmpty()) {
                breakQueue.clear();
            }
            if (breakPos != null) {
                breakPos = null;
            }
            if (!this.recentlyBroken.isEmpty()) {
                this.recentlyBroken.clear();
            }
            if (!this.pendingBroken.isEmpty()) {
                this.pendingBroken.clear();
            }
            this.externalDestroyLockTicks = 0;
            this.forceDelayedDestroy = false;
        }
    }

    public boolean isNeedHandle() {
        return !breakQueue.isEmpty() || breakPos != null;
    }

    public void onTick() {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return;
        }
        if (this.externalDestroyLockTicks > 0) {
            return;
        }
        if (breakPos == null && breakQueue.isEmpty()) {
            return;
        }
        if (breakPos == null) {
            while (!breakQueue.isEmpty()) {
                BlockPos pos = breakQueue.poll();
                if (pos == null) {
                    continue;
                }
                if (!ConfigUtils.canInteracted(pos) || !canBreakBlock(pos) || !breakRestriction(level.getBlockState(pos))) {
                    continue;
                }
                if (continueDestroyBlock(pos, Direction.DOWN) == BlockBreakResult.IN_PROGRESS) {
                    breakPos = pos;
                    break;
                }
            }
        } else {
            // 检查当前目标是否仍可破坏（如冰挖掘后生成水/流体，流体不可破坏）
            if (!canBreakBlock(breakPos)) {
                breakPos = null;
                this.forceDelayedDestroy = false;
                onTick();
                return;
            }
            if (continueDestroyBlock(breakPos, Direction.DOWN) != BlockBreakResult.IN_PROGRESS) {
                this.markRecentlyBroken(breakPos);
                breakPos = null;
                this.forceDelayedDestroy = false;
                onTick();
            }
        }
    }

    public boolean hasActiveDestroyTarget() {
        return this.breakPos != null;
    }

    public void suppressQueuedBreaks(int ticks) {
        this.externalDestroyLockTicks = Math.max(this.externalDestroyLockTicks, ticks);
    }

    public void markRecentlyBroken(BlockPos pos) {
        if (pos != null) {
            this.recentlyBroken.put(pos.immutable(), 2);
        }
    }

    public void markPendingBroken(BlockPos pos, int timeoutTicks) {
        if (pos != null) {
            this.pendingBroken.put(pos.immutable(), Math.max(timeoutTicks, 1));
        }
    }

    public void clearPendingBroken(BlockPos pos) {
        if (pos != null) {
            this.pendingBroken.remove(pos);
        }
    }

    public boolean isRecentlyBroken(BlockPos pos) {
        return pos != null && (this.recentlyBroken.containsKey(pos) || this.pendingBroken.containsKey(pos));
    }

    public BlockBreakResult continueDestroyBlock(final BlockPos blockPos, Direction direction, boolean localPrediction, boolean trackBreakPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        BlockBreakResult result = gameMode.litematica_printer$continueDestroyBlock(localPrediction, blockPos, direction, this.forceDelayedDestroy);
        if (trackBreakPos && result == BlockBreakResult.IN_PROGRESS) {
            breakPos = blockPos;
        }
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.forceDelayedDestroy = false;
        }
        return result;
    }

    public BlockBreakResult continueDestroyBlock(final BlockPos blockPos, Direction direction, boolean localPrediction) {
        return this.continueDestroyBlock(blockPos, direction, localPrediction, true);
    }

    public BlockBreakResult continueDestroyBlock(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlock(blockPos, direction, !Configs.Break.BREAK_USE_PACKET.getBooleanValue());
    }

    public BlockBreakResult continueDestroyBlock(BlockPos blockPos) {
        return this.continueDestroyBlock(blockPos, Direction.DOWN);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlockForMine(blockPos, direction, true);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos, Direction direction, boolean allowToolSwitch) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        if (gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        return gameMode.litematica_printer$continueDestroyBlockForMine(blockPos, direction, allowToolSwitch);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos) {
        return this.continueDestroyBlockForMine(blockPos, Direction.DOWN);
    }

    public boolean isPendingDelayedDestroy(BlockPos blockPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        return gameMode != null && gameMode.litematica_printer$isPendingDelayedDestroy(blockPos);
    }

    public BlockBreakResult continueDestroyBlockWithoutTracking(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlock(blockPos, direction, !Configs.Break.BREAK_USE_PACKET.getBooleanValue(), false);
    }

    public BlockBreakResult continueDestroyBlockWithoutTracking(BlockPos blockPos) {
        return this.continueDestroyBlockWithoutTracking(blockPos, Direction.DOWN);
    }

    public BlockBreakResult continueDestroyBlockWithoutToolSwitch(BlockPos blockPos, Direction direction, boolean trackBreakPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        if (gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        BlockBreakResult result = gameMode.litematica_printer$continueDestroyBlock(
                !Configs.Break.BREAK_USE_PACKET.getBooleanValue(),
                blockPos,
                direction,
                this.forceDelayedDestroy,
                false
        );
        if (trackBreakPos && result == BlockBreakResult.IN_PROGRESS) {
            breakPos = blockPos;
        }
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.forceDelayedDestroy = false;
        }
        return result;
    }

    public BlockBreakResult continueDestroyBlockWithoutToolSwitch(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlockWithoutToolSwitch(blockPos, direction, true);
    }

    public InteractionResult useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        return gameMode.litematica_printer$useItemOn(localPrediction, hand, blockHit);
    }

    public InteractionResult useItemOn(InteractionHand hand, BlockHitResult blockHit) {
        return this.useItemOn(!Configs.Placement.PRINT_USE_PACKET.getBooleanValue(), hand, blockHit);
    }
}
