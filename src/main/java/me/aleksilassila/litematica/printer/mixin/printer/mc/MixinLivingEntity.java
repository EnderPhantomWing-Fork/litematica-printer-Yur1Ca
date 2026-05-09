package me.aleksilassila.litematica.printer.mixin.printer.mc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends Entity {
    protected MixinLivingEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @ModifyExpressionValue(
            method = "getViewYRot",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/LivingEntity;yHeadRot:F",
                    opcode = Opcodes.GETFIELD
            )
    )
    private float litematica_printer$usePlayerBodyYawForView(float original) {
        return (Object) this instanceof Player ? this.getYRot() : original;
    }

    @ModifyExpressionValue(
            method = "getViewYRot",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/world/entity/LivingEntity;yHeadRotO:F",
                    opcode = Opcodes.GETFIELD
            )
    )
    private float litematica_printer$usePlayerPreviousBodyYawForView(float original) {
        return (Object) this instanceof Player ? this.yRotO : original;
    }
}
