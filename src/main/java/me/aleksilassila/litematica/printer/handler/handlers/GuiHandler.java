package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.Module;

public class GuiHandler extends Module {
    public static final String NAME = "gui";

    public GuiHandler() {
        super(NAME, null, Configs.Core.RENDER_HUD, null, false);
    }
}
