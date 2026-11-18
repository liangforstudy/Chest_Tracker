package io.github.liangforstudy.entitytracker.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The mod's settings, opened from Mod Menu (optional): the mod's keys, each rebindable here (the
 * same keys as in Controls), and a button to the streamer settings. Every setting also has a
 * command or a Controls entry, so nothing needs Mod Menu. Big buttons, no hover-only actions
 * (touch / PojavLauncher).
 */
public final class SettingsScreen extends Screen {
    private final @Nullable Screen parent;
    /** The key waiting for a new key press, or null. */
    private @Nullable KeyMapping listening;
    private final List<Button> keyButtons = new ArrayList<>();

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.entitytracker.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        keyButtons.clear();
        int w = 300, x = width / 2 - w / 2, y = 34;
        List<KeyMapping> keys = OpenTriggers.keys();
        for (KeyMapping key : keys) {
            int rowY = y;
            Button bind = Button.builder(keyLabel(key), b -> {
                listening = key;
                refreshLabels();
            }).bounds(x, rowY, w - 54, 20).build();
            keyButtons.add(addRenderableWidget(bind));
            Button reset = Button.builder(Component.translatable("controls.reset"), b -> {
                setKey(key, key.getDefaultKey());
            }).bounds(x + w - 50, rowY, 50, 20).build();
            addRenderableWidget(reset);
            y += 22;
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.settings.streamer_page"),
                        b -> {
                            listening = null;
                            minecraft.setScreen(new StreamerSettingsScreen(this));
                        })
                .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.settings.streamer.tip")))
                .bounds(x, y + 8, w, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    /** "Open tracker: K", with "> K <" while waiting for a key and red when another key shares it. */
    private Component keyLabel(KeyMapping key) {
        Component name = Component.translatable(key.getName());
        Component bound = OpenTriggers.keyName(key).copy();
        if (key == listening) {
            bound = Component.literal("> ").append(bound.copy().withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE))
                    .append(" <").withStyle(ChatFormatting.YELLOW);
        } else if (conflicts(key)) {
            bound = bound.copy().withStyle(ChatFormatting.RED);
        }
        return CommonComponents.optionNameValue(name, bound);
    }

    private boolean conflicts(KeyMapping key) {
        if (key.isUnbound()) return false;
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != key && key.same(other)) return true;
        }
        return false;
    }

    private void setKey(KeyMapping key, InputConstants.Key value) {
        key.setKey(value);
        KeyMapping.resetMapping();
        minecraft.options.save();
        listening = null;
        refreshLabels();
    }

    private void refreshLabels() {
        List<KeyMapping> keys = OpenTriggers.keys();
        for (int i = 0; i < keyButtons.size(); i++) keyButtons.get(i).setMessage(keyLabel(keys.get(i)));
    }

    /** While waiting: Escape unbinds (as in Controls), any other key is the new one. */
    @Override
    public boolean keyPressed(KeyEvent input) {
        if (listening != null) {
            setKey(listening, input.key() == GLFW.GLFW_KEY_ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(input));
            return true;
        }
        return super.keyPressed(input);
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
        context.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        context.drawCenteredString(font, Component.translatable("screen.entitytracker.settings.keys"), width / 2, 22, 0xFFAAAAAA);
    }
}
