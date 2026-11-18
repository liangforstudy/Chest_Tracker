package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.StreamerMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Streamer mode's own settings page, under the mod's settings (also /etracker streamer ...). */
public final class StreamerSettingsScreen extends Screen {
    private final Screen parent;

    public StreamerSettingsScreen(Screen parent) {
        super(Component.translatable("screen.entitytracker.settings.streamer_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 4;
        addRenderableWidget(Button.builder(streamerLabel(), b -> {
                    StreamerMode.toggle();
                    b.setMessage(streamerLabel());
                })
                .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.settings.streamer.tip")))
                .bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(clipboardLabel(), b -> {
                    StreamerMode.setAllowClipboard(!StreamerMode.allowClipboard());
                    b.setMessage(clipboardLabel());
                })
                .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.settings.clipboard.tip")))
                .bounds(x, y + 24, w, 20).build());
        // Leaves the settings altogether (Esc goes back one page).
        addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.exit"), b -> parent.onClose())
                .bounds(width / 2 - 75, height - 28, 150, 20).build());
    }

    private static Component streamerLabel() {
        return CommonComponents.optionNameValue(Component.translatable("screen.entitytracker.settings.streamer"),
                Component.translatable(StreamerMode.on() ? "screen.entitytracker.settings.on" : "screen.entitytracker.settings.off"));
    }

    private static Component clipboardLabel() {
        return CommonComponents.optionNameValue(Component.translatable("screen.entitytracker.settings.clipboard"),
                Component.translatable(StreamerMode.allowClipboard() ? "screen.entitytracker.settings.allowed"
                        : "screen.entitytracker.settings.blocked"));
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
    }
}
