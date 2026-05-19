package me.aleksilassila.litematica.printer.render;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.zxy.utils.HighlightBlockRenderer;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.mods.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class InventorySchematicHighlighter {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final String HIGHLIGHT_ID = "inventory_schematic";

    private static long lastUpdateTick = Long.MIN_VALUE;

    private InventorySchematicHighlighter() {
    }

    public static void init() {
        HighlightBlockRenderer.createHighlightBlockList(HIGHLIGHT_ID, Configs.Print.INVENTORY_SCHEMATIC_HIGHLIGHT_COLOR);
    }

    public static void tick(long currentTick) {
        if (!Configs.Print.INVENTORY_SCHEMATIC_HIGHLIGHT.getBooleanValue()) {
            clear();
            return;
        }

        int interval = Math.max(1, Configs.Print.INVENTORY_SCHEMATIC_HIGHLIGHT_INTERVAL.getIntegerValue());
        if (currentTick - lastUpdateTick < interval) {
            return;
        }
        lastUpdateTick = currentTick;

        LocalPlayer player = CLIENT.player;
        ClientLevel level = CLIENT.level;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (player == null || level == null || schematic == null) {
            clear();
            return;
        }

        Set<BlockPos> highlightPositions = new LinkedHashSet<>();
        Map<Item, Boolean> availableItems = new HashMap<>();
        boolean creative = PlayerUtils.getAbilities(player).instabuild;
        double interactionRange = PlayerUtils.getPlayerBlockInteractionRange();
        PrinterBox scanBox = new PrinterBox(
                (int) Math.floor(player.getX() - interactionRange),
                (int) Math.floor(player.getEyeY() - interactionRange),
                (int) Math.floor(player.getZ() - interactionRange),
                (int) Math.floor(player.getX() + interactionRange),
                (int) Math.floor(player.getEyeY() + interactionRange),
                (int) Math.floor(player.getZ() + interactionRange)
        );

        for (BlockPos pos : scanBox) {
            BlockPos blockPos = pos.immutable();
            if (!PlayerUtils.isWithinBlockInteractionRange(player, blockPos, 0.0D)) {
                continue;
            }
            if (!LitematicaUtils.isPositionWithinRange(blockPos) || !LitematicaUtils.isSchematicBlock(blockPos)) {
                continue;
            }
            if (canPlaceFromInventory(level, schematic, blockPos, player, creative, availableItems)) {
                highlightPositions.add(blockPos);
            }
        }

        HighlightBlockRenderer.setPos(HIGHLIGHT_ID, highlightPositions);
    }

    private static boolean canPlaceFromInventory(ClientLevel level, WorldSchematic schematic, BlockPos pos, LocalPlayer player,
                                                 boolean creative, Map<Item, Boolean> availableItems) {
        SchematicBlockContext context = new SchematicBlockContext(CLIENT, level, schematic, pos);
        if (context.compare() != BlockMatchResult.MISSING) {
            return false;
        }

        BlockState requiredState = context.requiredState;
        Item requiredItem = requiredState.getBlock().asItem();
        if (requiredItem == Items.AIR) {
            return false;
        }
        if (creative) {
            return true;
        }
        return availableItems.computeIfAbsent(requiredItem, item ->
                player.getMainHandItem().is(item) || player.getOffhandItem().is(item) || player.getInventory().countItem(item) > 0);
    }

    private static void clear() {
        HighlightBlockRenderer.clear(HIGHLIGHT_ID);
    }
}
