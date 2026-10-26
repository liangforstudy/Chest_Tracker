package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The inventory key while riding a chest boat (or horse) opens the vehicle's inventory without
 * any right-click; treat it as an interaction with the vehicle so the screen can be bound.
 */
@Mixin(LocalPlayer.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "sendOpenInventory", at = @At("HEAD"))
    private void entitytracker$onOpenRidingInventory(CallbackInfo ci) {
        Entity vehicle = ((LocalPlayer) (Object) this).getVehicle();
        if (vehicle != null) EntityTrackerClient.interactions().set(vehicle);
    }
}
