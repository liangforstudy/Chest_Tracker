package io.github.liangforstudy.entitytracker.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.liangforstudy.entitytracker.StreamerMode;
import io.github.liangforstudy.entitytracker.trace.Tracers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Ways to open the tracker screen: a keybind (K by default), a button in the inventory (for
 * touch screens / PojavLauncher) and {@code /etracker}.
 */
public final class OpenTriggers {
    private OpenTriggers() {}

    /** The mod's section in Controls > Key Binds (the settings screen opens it there). */
    private static KeyMapping.Category category;

    public static KeyMapping.Category category() {
        return category;
    }

    /**
     * The key's name as shown to the player. The game names number-pad keys like the top-row ones
     * ("5"), so those say "Keypad 5" instead.
     */
    public static Component keyName(KeyMapping key) {
        String id = key.saveString();
        return id.startsWith("key.keyboard.keypad.") ? Component.translatable(id) : key.getTranslatedKeyMessage();
    }

    public static void register() {
        category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("entitytracker", "main"));
        KeyMapping open = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.open",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        // Unbound by default: the Trades button on the tracker screen is the main way in.
        KeyMapping trades = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.trades",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        // Unbound too: hides or shows the tracer lines without forgetting them (also a button in the tracker).
        KeyMapping toggleTracers = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.toggle_tracers",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        // A second "open" key, Numpad 5 by default, for keyboards where K is taken.
        KeyMapping openAlt = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.open_alt",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_KP_5, category));
        // Unbound: hides absolute coordinates everywhere (also /etracker streamer).
        KeyMapping streamer = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.toggle_streamer",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleTracers.consumeClick()) {
                Tracers.toggle();
                Messages.actionBar(Tracers.status());
            }
            while (streamer.consumeClick()) {
                StreamerMode.toggle();
                Messages.actionBar(Component.translatable(StreamerMode.on()
                        ? "screen.entitytracker.streamer.on" : "screen.entitytracker.streamer.off"));
            }
            while (open.consumeClick() | openAlt.consumeClick()) {
                if (client.screen == null && client.level != null) client.setScreen(new TrackerScreen());
            }
            while (trades.consumeClick()) {
                if (client.screen == null && client.level != null) client.setScreen(new TradesScreen());
            }
        });

        ContainerHighlight.register();
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) return;
            // Top-right corner of the screen, clear of the inventory and the recipe book.
            Screens.getButtons(screen).add(Button.builder(Component.literal("ET"),
                            b -> client.setScreen(new TrackerScreen()))
                    .bounds(width - 26, 6, 20, 20)
                    .tooltip(Tooltip.create(openTooltip(open, openAlt)))
                    .build());
        });
    }

    /**
     * "Open Entity Container Tracker (or press "K" to open it as well)", naming the open keys as
     * bound right now (built when the inventory opens, so a rebind in Controls shows next time).
     */
    private static Component openTooltip(KeyMapping... keys) {
        java.util.List<Component> bound = new java.util.ArrayList<>();
        for (KeyMapping k : keys) if (!k.isUnbound()) bound.add(keyName(k));
        return switch (bound.size()) {
            case 0 -> Component.translatable("screen.entitytracker.open_button");
            case 1 -> Component.translatable("screen.entitytracker.open_button.key", bound.get(0));
            default -> Component.translatable("screen.entitytracker.open_button.keys", bound.get(0), bound.get(1));
        };
    }

    /** For /etracker: the chat screen closes after the command runs, so open on the next tick. */
    public static void openLater() {
        openLater(new TrackerScreen());
    }

    public static void openLater(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        client.schedule(() -> client.setScreen(screen));
    }
}
