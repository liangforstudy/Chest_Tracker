package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.trace.Tracers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the tracer lines each frame, right after the camera is placed for it, so they start at
 * this frame's crosshair. 26.2 places the camera while it extracts the frame to draw.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    //? if >=26.2 {
    /*@Inject(method = "extract", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V"))
    private void entitytracker$tracers(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
    *///?} else {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/renderer/GameRenderer;extractCamera(F)V"))
    private void entitytracker$tracers(DeltaTracker deltaTracker, CallbackInfo ci) {
    //?}
        Tracers.emit(Minecraft.getInstance(), deltaTracker.getGameTimeDeltaPartialTick(false));
    }
}
