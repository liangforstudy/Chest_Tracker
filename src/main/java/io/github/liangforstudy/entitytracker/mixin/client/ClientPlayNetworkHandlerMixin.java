package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket;
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

    /** Same as above: TAIL only runs on the render thread, after the screen got the offers. */
    @Inject(method = "onSetTradeOffers", at = @At("TAIL"))
    private void entitytracker$afterTradeOffers(SetTradeOffersS2CPacket packet, CallbackInfo ci) {
        EntityTrackerClient.trades().onOffers(packet.getSyncId(), packet.getOffers());
    }
}
