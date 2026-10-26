package io.github.liangforstudy.entitytracker.gui;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * "Storage | Trades" tabs at the top of both screens, in the same place on each, so switching is
 * one tap in a fixed spot. The open tab is inactive and underlined.
 */
final class ScreenTabs {
    static final int Y = 2, HEIGHT = 18, WIDTH = 90;

    private ScreenTabs() {}

    static List<Button> create(Minecraft client, int screenWidth, boolean trades) {
        int x = screenWidth / 2 - WIDTH - 2;
        Button storage = Button.builder(Component.translatable("screen.entitytracker.tab.storage"),
                b -> client.setScreen(new TrackerScreen())).bounds(x, Y, WIDTH, HEIGHT).build();
        Button offers = Button.builder(Component.translatable("screen.entitytracker.tab.trades"),
                b -> client.setScreen(new TradesScreen())).bounds(x + WIDTH + 4, Y, WIDTH, HEIGHT).build();
        storage.active = trades;
        offers.active = !trades;
        return List.of(storage, offers);
    }

    static void drawUnderline(GuiGraphics context, int screenWidth, boolean trades) {
        int x = screenWidth / 2 - WIDTH - 2 + (trades ? WIDTH + 4 : 0);
        context.fill(x + 4, Y + HEIGHT, x + WIDTH - 4, Y + HEIGHT + 1, 0xFFFFFFFF);
    }
}
