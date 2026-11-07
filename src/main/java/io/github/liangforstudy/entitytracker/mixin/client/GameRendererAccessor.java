package io.github.liangforstudy.entitytracker.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
//? if >=26.2 {
/*import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/**
 * The game's own view bobbing (walking) and hurt shake, so tracers can start where the crosshair
 * really is on screen: both move the whole picture, not the camera.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    //? if >=26.2 {
    /*@Accessor("gameRenderState")
    GameRenderState entitytracker$gameRenderState();

    @Invoker("bobHurt")
    void entitytracker$bobHurt(net.minecraft.client.renderer.state.level.CameraRenderState camera, PoseStack pose);

    @Invoker("bobView")
    void entitytracker$bobView(net.minecraft.client.renderer.state.level.CameraRenderState camera, PoseStack pose);
    *///?} else {
    @Invoker("bobHurt")
    void entitytracker$bobHurt(PoseStack pose, float partialTick);

    @Invoker("bobView")
    void entitytracker$bobView(PoseStack pose, float partialTick);
    //?}
}
