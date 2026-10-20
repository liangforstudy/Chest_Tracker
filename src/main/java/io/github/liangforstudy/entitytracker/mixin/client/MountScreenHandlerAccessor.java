package io.github.liangforstudy.entitytracker.mixin.client;

import net.minecraft.entity.LivingEntity;
import net.minecraft.screen.MountScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The client builds mount screens around the real entity, so horse/donkey/llama screens name their owner. */
@Mixin(MountScreenHandler.class)
public interface MountScreenHandlerAccessor {
    @Accessor("mount")
    LivingEntity entitytracker$getMount();
}
