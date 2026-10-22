package io.github.liangforstudy.entitytracker.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads and writes memory banks under {@code <game dir>/entity-tracker/} (CLAUDE.md 4.10):
 * atomic writes, daily rolling backups, and a corrupt file is set aside rather than replaced
 * with an empty bank. Nothing in here ever deletes a bank.
 */
public final class BankStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int FORMAT_VERSION = 1;

    private final Path root;

    public BankStorage(Path gameDir) {
        this.root = gameDir.resolve("entity-tracker");
    }

    public Path bankFile(String key) {
        return root.resolve("banks").resolve(key + ".json");
    }

    private Path backupDir(String key) {
        return root.resolve("backups").resolve(key);
    }

    /**
     * Server name after aliases; never the resolved IP or world name. Not the account: all of the
     * player's accounts share one bank per server.
     */
    public static String keyFor(String server) {
        return server.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /**
     * Maps a server-list address to the name its bank is stored under. Addresses listed together
     * in server-aliases.json share one bank, e.g. 9b9t.com and 9b9t.org are the same server.
     */
    public String resolveServer(String address) {
        String normalized = normalizeAddress(address);
        for (Map.Entry<String, JsonElement> group : loadAliases().entrySet()) {
            if (!group.getValue().isJsonArray()) continue;
            for (JsonElement alias : group.getValue().getAsJsonArray()) {
                if (normalizeAddress(alias.getAsString()).equals(normalized)) return group.getKey();
            }
        }
        return normalized;
    }

    static String normalizeAddress(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - ":25565".length());
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    private JsonObject loadAliases() {
        Path file = root.resolve("server-aliases.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(root);
                JsonObject defaults = new JsonObject();
                JsonArray nineB = new JsonArray();
                nineB.add("9b9t.com");
                nineB.add("9b9t.org");
                defaults.add("9b9t", nineB);
                Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
                return defaults;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            // A broken alias file must not stop the bank from opening; it just opens unaliased.
            EntityTrackerClient.LOGGER.error("Could not read {}, ignoring aliases", file, e);
            return new JsonObject();
        }
    }

    public record LoadResult(MemoryBank bank, String reason, @Nullable String warning) {}

    public LoadResult load(String server) {
        String key = keyFor(server);
        Path file = bankFile(key);
        if (!Files.exists(file)) return mergeLegacy(key, server);
        try {
            return new LoadResult(read(file, key, server, null), "loaded existing file", null);
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Bank file {} is unreadable", file, e);
            Path corrupt = file.resolveSibling(key + ".json.corrupt-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, corrupt);
            } catch (IOException moveError) {
                EntityTrackerClient.LOGGER.error("Could not set aside corrupt bank {}", file, moveError);
            }
            for (Path backup : backupsNewestFirst(key)) {
                try {
                    MemoryBank bank = read(backup, key, server, null);
                    bank.markDirty(); // write the restored data back as the live file
                    return new LoadResult(bank, "restored from backup " + backup.getFileName(),
                            "Entity Tracker: bank file was unreadable, restored backup " + backup.getFileName()
                                    + ". The broken file was kept as " + corrupt.getFileName());
                } catch (Exception backupError) {
                    EntityTrackerClient.LOGGER.error("Backup {} is unreadable too", backup, backupError);
                }
            }
            return new LoadResult(new MemoryBank(key, server), "file unreadable, no usable backup",
                    "Entity Tracker: bank file was unreadable and no backup worked. It was kept as "
                            + corrupt.getFileName() + "; starting empty.");
        }
    }

    /**
     * Banks used to be per server + account (key_uuid.json). If only those exist, merge them into
     * the shared bank; the old files are left untouched.
     */
    private LoadResult mergeLegacy(String key, String server) {
        Pattern legacyName = Pattern.compile(Pattern.quote(key) + "_([0-9a-f-]{36})\\.json");
        MemoryBank merged = new MemoryBank(key, server);
        List<String> used = new ArrayList<>();
        Path dir = root.resolve("banks");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    Matcher m = legacyName.matcher(p.getFileName().toString());
                    if (!m.matches()) continue;
                    try {
                        MemoryBank old = read(p, key, server, UUID.fromString(m.group(1)));
                        for (EntityRecord r : old.all()) merged.mergeEntity(r);
                        for (BlockRecord r : old.allBlocks()) merged.mergeBlock(r);
                        old.accounts.forEach(merged.accounts::putIfAbsent);
                        merged.playDays.addAll(old.playDays);
                        used.add(p.getFileName().toString());
                    } catch (Exception e) {
                        EntityTrackerClient.LOGGER.error("Could not read old bank {}; left as it is", p, e);
                    }
                }
            } catch (IOException e) {
                EntityTrackerClient.LOGGER.error("Could not list {}", dir, e);
            }
        }
        if (used.isEmpty()) return new LoadResult(merged, "new bank (no file yet)", null);
        merged.markDirty();
        return new LoadResult(merged, "merged old per-account banks " + used + " (old files kept)", null);
    }

    /** {@code legacyOwner}: the account of an old per-account file, for its ownerless ender chest. */
    private static MemoryBank read(Path file, String key, String server, @Nullable UUID legacyOwner) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        MemoryBank bank = new MemoryBank(key, server);
        if (root.has("accounts")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("accounts").entrySet()) {
                bank.accounts.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            }
        }
        if (legacyOwner == null && root.has("player")) legacyOwner = UUID.fromString(root.get("player").getAsString());
        if (legacyOwner != null) bank.accounts.putIfAbsent(legacyOwner, legacyOwner.toString().substring(0, 8));
        JsonObject entities = root.getAsJsonObject("entities");
        for (Map.Entry<String, JsonElement> e : entities.entrySet()) {
            UUID uuid = UUID.fromString(e.getKey());
            bank.put(EntityRecord.fromJson(uuid, e.getValue().getAsJsonObject()));
        }
        if (root.has("blocks")) {
            for (JsonElement e : root.getAsJsonArray("blocks")) {
                BlockRecord r = BlockRecord.fromJson(e.getAsJsonObject());
                if (r.global && r.owner == null) r.owner = legacyOwner;
                bank.putBlock(r);
            }
        }
        if (root.has("playDays")) {
            for (JsonElement e : root.getAsJsonArray("playDays")) bank.playDays.add(e.getAsString());
        }
        if (root.has("lastAreaAction")) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("lastAreaAction")) keys.add(e.getAsString());
            bank.setLastAreaAction(keys);
            bank.clearDirty();
        }
        return bank;
    }

    /** Saves right away on this thread (e.g. when the game is closing). */
    public void save(MemoryBank bank) {
        JsonObject root = snapshot(bank);
        bank.clearDirty();
        if (!write(bank.key, root, bank.size())) bank.markDirty();
    }

    /**
     * The bank as JSON, taken on the game thread. Cheap: item data is shared, not copied (it's
     * never changed in place), so the slow part, writing the file, can run on another thread.
     */
    public JsonObject snapshot(MemoryBank bank) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("server", bank.server);
        JsonObject accounts = new JsonObject();
        bank.accounts.forEach((uuid, name) -> accounts.addProperty(uuid.toString(), name));
        root.add("accounts", accounts);
        JsonObject entities = new JsonObject();
        for (EntityRecord r : bank.all()) entities.add(r.uuid.toString(), r.toJson());
        root.add("entities", entities);
        JsonArray blocks = new JsonArray();
        for (BlockRecord r : bank.allBlocks()) blocks.add(r.toJson());
        root.add("blocks", blocks);
        JsonArray lastArea = new JsonArray();
        for (String k : bank.lastAreaAction()) lastArea.add(k);
        root.add("lastAreaAction", lastArea);
        JsonArray playDays = new JsonArray();
        bank.playDays.forEach(playDays::add);
        root.add("playDays", playDays);
        return root;
    }

    /** Writes a snapshot (backup, temp file, atomic rename). Safe off the game thread. */
    public boolean write(String key, JsonObject root, int size) {
        Path file = bankFile(key);
        try {
            Files.createDirectories(file.getParent());
            backupIfNeeded(key, file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            EntityTrackerClient.LOGGER.info("Saved bank {} ({} entities) to {}", key, size, file);
            return true;
        } catch (IOException e) {
            EntityTrackerClient.LOGGER.error("Failed to save bank {} to {}", key, file, e);
            return false;
        }
    }

    /** First save of each day copies the current file into backups/, keeping the newest few. */
    private void backupIfNeeded(String key, Path file) throws IOException {
        if (!Files.exists(file)) return;
        Path dir = backupDir(key);
        Path today = dir.resolve(LocalDate.now() + ".json");
        if (Files.exists(today)) return;
        Files.createDirectories(dir);
        Files.copy(file, today);
        List<Path> backups = backupsNewestFirst(key);
        for (int i = Settings.backupsToKeep; i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private List<Path> backupsNewestFirst(String key) {
        Path dir = backupDir(key);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
