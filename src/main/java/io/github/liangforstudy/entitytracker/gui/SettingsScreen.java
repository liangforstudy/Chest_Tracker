package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.mixin.client.KeyBindsScreenAccessor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The mod's settings, opened from Mod Menu (optional). The keys live in the game's own Controls
 * screen; the button here opens it at this mod's section. Every other setting also has a command,
 * so nothing needs Mod Menu. Big buttons, no hover-only actions (touch / PojavLauncher).
 */
public final class SettingsScreen extends Screen {
    private final @Nullable Screen parent;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.entitytracker.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 260, x = width / 2 - w / 2, y = height / 4;
        addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.settings.keys_button"),
                        b -> openKeyBinds())
                .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.settings.keys_button.tip")))
                .bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.settings.streamer_page"),
                        b -> minecraft.setScreen(new StreamerSettingsScreen(this)))
                .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.settings.streamer.tip")))
                .bounds(x, y + 24, w, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    /** Opens Options > Controls > Key Binds scrolled so this mod's section heads the list. */
    private void openKeyBinds() {
        KeyBindsScreen screen = new KeyBindsScreen(this, minecraft.options);
        minecraft.setScreen(screen); // builds the list
        KeyBindsList list = ((KeyBindsScreenAccessor) screen).entitytracker$keyBindsList();
        String label = OpenTriggers.category().label().getString();
        List<KeyBindsList.Entry> entries = list.children();
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof KeyBindsList.CategoryEntry header)) continue;
            for (GuiEventListener child : header.children()) {
                if (child instanceof AbstractWidget w && w.getMessage().getString().equals(label)) {
                    list.setScrollAmount(list.scrollAmount() + list.getRowTop(i) - list.getY());
                    return;
                }
            }
        }
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
