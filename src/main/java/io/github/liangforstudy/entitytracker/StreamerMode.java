package io.github.liangforstudy.entitytracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streamer mode: absolute coordinates are never drawn (lists, detail views, chat, the "copied"
 * message), only distances, so a livestream can't give a base away. Toggled with
 * {@code /etracker streamer}, the "Toggle streamer mode" key or the settings screen; remembered in
 * {@code <game dir>/entity-tracker/settings.json}.
 * <p>
 * While it's on, the Copy buttons leave the clipboard alone (pasting on stream would show the
 * coordinates), unless the player allows it: {@code /etracker streamer allowclipboard true}.
 */
public final class StreamerMode {
    private static volatile boolean on;
    private static volatile boolean allowClipboard = false;
    private static Path file;

    private StreamerMode() {}

    public static boolean on() {
        return on;
    }

    /** Whether Copy x y z / Copy x z still fill the clipboard in streamer mode. */
    public static boolean allowClipboard() {
        return allowClipboard;
    }

    public static void load(Path root) {
        file = root.resolve("settings.json");
        try {
            if (Files.exists(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                on = json.has("streamerMode") && json.get("streamerMode").getAsBoolean();
                allowClipboard = json.has("streamerAllowClipboard") && json.get("streamerAllowClipboard").getAsBoolean();
            }
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Could not read {}", file, e);
        }
    }

    public static void set(boolean value) {
        on = value;
        save("streamerMode", value);
    }

    public static void toggle() {
        set(!on);
    }

    public static void setAllowClipboard(boolean value) {
        allowClipboard = value;
        save("streamerAllowClipboard", value);
    }

    private static void save(String key, boolean value) {
        if (file == null) return;
        try {
            JsonObject json = Files.exists(file)
                    ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            json.addProperty(key, value);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Could not write {}", file, e);
        }
    }
}
