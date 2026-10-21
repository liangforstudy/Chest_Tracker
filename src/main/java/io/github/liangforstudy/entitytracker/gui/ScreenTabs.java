package io.github.liangforstudy.entitytracker.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * "Storage | Trades" tabs at the top of both screens, in the same place on each, so switching is
 * one tap in a fixed spot. The open tab is inactive and underlined.
 */
final class ScreenTabs {
    static final int Y = 2, HEIGHT = 18, WIDTH = 90;

    private ScreenTabs() {}

    static List<ButtonWidget> create(MinecraftClient client, int screenWidth, boolean trades) {
        int x = screenWidth / 2 - WIDTH - 2;
        ButtonWidget storage = ButtonWidget.builder(Text.translatable("screen.entitytracker.tab.storage"),
                b -> client.setScreen(new TrackerScreen())).dimensions(x, Y, WIDTH, HEIGHT).build();
        ButtonWidget offers = ButtonWidget.builder(Text.translatable("screen.entitytracker.tab.trades"),
                b -> client.setScreen(new TradesScreen())).dimensions(x + WIDTH + 4, Y, WIDTH, HEIGHT).build();
        storage.active = trades;
        offers.active = !trades;
        return List.of(storage, offers);
    }

    static void drawUnderline(DrawContext context, int screenWidth, boolean trades) {
        int x = screenWidth / 2 - WIDTH - 2 + (trades ? WIDTH + 4 : 0);
        context.fill(x + 4, Y + HEIGHT, x + WIDTH - 4, Y + HEIGHT + 1, 0xFFFFFFFF);
    }
}
