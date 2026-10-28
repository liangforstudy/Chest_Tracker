package io.github.liangforstudy.entitytracker.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Messages to the player that only this client sees; dropped when not in a world. */
public final class Messages {
    private Messages() {}

    public static void chat(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        //? if >=26.2 {
        /*player.sendSystemMessage(message);
        *///?} else {
        player.displayClientMessage(message, false);
        //?}
    }

    /** Above the hotbar, briefly. */
    public static void actionBar(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        //? if >=26.2 {
        /*player.sendOverlayMessage(message);
        *///?} else {
        player.displayClientMessage(message, true);
        //?}
    }
}
