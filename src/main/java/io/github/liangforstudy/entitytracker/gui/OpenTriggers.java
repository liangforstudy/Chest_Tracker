package io.github.liangforstudy.entitytracker.gui;

import com.mojang.blaze3d.platform.InputConstants;
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

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("entitytracker", "main"));
        KeyMapping open = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.open",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        // Unbound by default: the Trades button on the tracker screen is the main way in.
        KeyMapping trades = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.entitytracker.trades",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.consumeClick()) {
                if (client.screen == null && client.level != null) client.setScreen(new TrackerScreen());
            }
            while (trades.consumeClick()) {
                if (client.screen == null && client.level != null) client.setScreen(new TradesScreen());
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen)) return;
            // Top-right corner of the screen, clear of the inventory and the recipe book.
            Screens.getButtons(screen).add(Button.builder(Component.literal("ET"),
                            b -> client.setScreen(new TrackerScreen()))
                    .bounds(width - 26, 6, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("screen.entitytracker.open_button")))
                    .build());
        });
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
