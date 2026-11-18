package io.github.liangforstudy.entitytracker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streamer mode: absolute coordinates are never drawn (lists, detail views, chat, the "copied"
 * message), only distances, so a livestream can't give a base away. Toggled with
 * {@code /etracker streamer} or the "Toggle streamer mode" key; remembered in
 * {@code <game dir>/entity-tracker/settings.json}.
 */
public final class StreamerMode {
    private static volatile boolean on;
    private static Path file;

    private StreamerMode() {}

    public static boolean on() {
        return on;
    }

    public static void load(Path root) {
        file = root.resolve("settings.json");
        try {
            if (Files.exists(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                on = json.has("streamerMode") && json.get("streamerMode").getAsBoolean();
            }
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Could not read {}", file, e);
        }
    }

    public static void set(boolean value) {
        on = value;
        if (file == null) return;
        try {
            JsonObject json = Files.exists(file)
                    ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            json.addProperty("streamerMode", value);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Could not write {}", file, e);
        }
    }

    public static void toggle() {
        set(!on);
    }
}
