package me.aleksilassila.litematica.printer.handler.handlers.print;

import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;

public interface PrintTaskAction {
    default void onQueued(SchematicBlockContext context, Action action) {
    }

    void onSuccess(SchematicBlockContext context, Action action);

    void onFailure(SchematicBlockContext context, Action action);

    default boolean stopIterationAfterAction() {
        return true;
    }
}
