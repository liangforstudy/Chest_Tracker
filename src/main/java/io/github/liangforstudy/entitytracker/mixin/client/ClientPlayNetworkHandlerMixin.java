package io.github.liangforstudy.entitytracker.mixin.client;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    /** TAIL is only reached on the render thread; the network-thread call bails out early. */
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void entitytracker$afterInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        EntityTrackerClient.screens().onInventorySync(packet.containerId());
    }

    /** Same as above: TAIL only runs on the render thread, after the screen got the offers. */
    @Inject(method = "handleMerchantOffers", at = @At("TAIL"))
    private void entitytracker$afterTradeOffers(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        EntityTrackerClient.trades().onOffers(packet.getContainerId(), packet.getOffers());
    }
}
