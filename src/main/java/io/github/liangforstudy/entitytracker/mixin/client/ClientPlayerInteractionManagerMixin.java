package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the interaction methods themselves rather than Fabric's Use*Callback, which only fires
 * for a real right-click; other mods (and test tools) call these methods directly.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin {
    //? if <26.2 {
    @Inject(method = "interact", at = @At("HEAD"))
    private void entitytracker$onInteractEntity(Player player, Entity entity, InteractionHand hand,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        EntityTrackerClient.interactions().set(entity);
        EntityTrackerClient.displays().onInteractEntity(entity);
    }
    //?}

    // 26.2 merged interact and interactAt into one interact that takes the hit
    //? if >=26.2 {
    /*@Inject(method = "interact", at = @At("HEAD"))
    *///?} else {
    @Inject(method = "interactAt", at = @At("HEAD"))
    //?}
    private void entitytracker$onInteractEntityAt(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
                                                  CallbackInfoReturnable<InteractionResult> cir) {
        EntityTrackerClient.interactions().set(entity);
        EntityTrackerClient.displays().onInteractEntity(entity);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void entitytracker$onInteractBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        EntityTrackerClient.interactions().setBlock(hit.getBlockPos());
        EntityTrackerClient.displays().onInteractBlock(player, hand, hit);
    }
}
