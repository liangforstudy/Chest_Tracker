package io.github.liangforstudy.entitytracker.gui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Ways to open the tracker screen: a keybind (K by default), a button in the inventory (for
 * touch screens / PojavLauncher) and {@code /etracker}.
 */
public final class OpenTriggers {
    private OpenTriggers() {}

    public static void register() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("entitytracker", "main"));
        KeyBinding open = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.entitytracker.open",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        // Unbound by default: the Trades button on the tracker screen is the main way in.
        KeyBinding trades = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.entitytracker.trades",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (open.wasPressed()) {
                if (client.currentScreen == null && client.world != null) client.setScreen(new TrackerScreen());
            }
            while (trades.wasPressed()) {
                if (client.currentScreen == null && client.world != null) client.setScreen(new TradesScreen());
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeInventoryScreen)) return;
            // Top-right corner of the screen, clear of the inventory and the recipe book.
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("ET"),
                            b -> client.setScreen(new TrackerScreen()))
                    .dimensions(width - 26, 6, 20, 20)
                    .tooltip(Tooltip.of(Text.translatable("screen.entitytracker.open_button")))
                    .build());
        });
    }

    /** For /etracker: the chat screen closes after the command runs, so open on the next tick. */
    public static void openLater() {
        openLater(new TrackerScreen());
    }

    public static void openLater(Screen screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.send(() -> client.setScreen(screen));
    }
}
