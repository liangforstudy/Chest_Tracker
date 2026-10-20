package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    /** TAIL is only reached on the render thread; the network-thread call bails out early. */
    @Inject(method = "onInventory", at = @At("TAIL"))
    private void entitytracker$afterInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        EntityTrackerClient.screens().onInventorySync(packet.syncId());
    }
}
