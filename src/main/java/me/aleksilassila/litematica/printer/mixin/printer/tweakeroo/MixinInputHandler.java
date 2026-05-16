package me.aleksilassila.litematica.printer.mixin.printer.tweakeroo;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "fi.dy.masa.tweakeroo.event.InputHandler", remap = false)
public abstract class MixinInputHandler {
    @Redirect(
            method = "onMouseClick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;isCreative()Z"
            )
    )
    private boolean litematica_printer$allowAngelBlockOutsideCreative(LocalPlayer player) {
        return player.isCreative() || player.getAbilities().mayBuild;
    }
}
