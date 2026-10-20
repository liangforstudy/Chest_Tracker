package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the interaction methods themselves rather than Fabric's Use*Callback, which only fires
 * for a real right-click; other mods (and test tools) call these methods directly.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "interactEntity", at = @At("HEAD"))
    private void entitytracker$onInteractEntity(PlayerEntity player, Entity entity, Hand hand,
                                                CallbackInfoReturnable<ActionResult> cir) {
        EntityTrackerClient.interactions().set(entity);
    }

    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"))
    private void entitytracker$onInteractEntityAt(PlayerEntity player, Entity entity, EntityHitResult hit, Hand hand,
                                                  CallbackInfoReturnable<ActionResult> cir) {
        EntityTrackerClient.interactions().set(entity);
    }

    @Inject(method = "interactBlock", at = @At("HEAD"))
    private void entitytracker$onInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                               CallbackInfoReturnable<ActionResult> cir) {
        EntityTrackerClient.interactions().setBlock(hit.getBlockPos());
    }
}
