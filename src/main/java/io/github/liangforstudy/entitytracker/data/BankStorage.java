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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** Bank files are written compact: pretty printing tripled their size. */
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
    /**
     * 1: every stack written out in full. 2: each distinct stack once, in a table ({@link StackStore}).
     * A file from a newer format than this is never written over (see {@link #load}).
     */
    private static final int FORMAT_VERSION = 2;

    private final Path root;
    /**
     * 9b9t addresses added to the defaults after the first release. 2b2t.com redirects to 9b9t (the
     * real 2b2t is 2b2t.org, which stays separate); minecraft.9b9t.org is where 9b9t.org points,
     * and what a launcher's quick-join can connect with.
     */
    private static final List<String> LATER_9B9T = List.of("2b2t.com", "minecraft.9b9t.org");

    /** Banks whose file is from a newer version of the mod: opened empty, never saved. */
    private final Set<String> readOnly = ConcurrentHashMap.newKeySet();

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
     * in server-aliases.json share one bank, e.g. 9b9t.com, 9b9t.org and 2b2t.com are the same server.
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

    /** Every address that shares the bank called {@code server}, including the name itself. */
    public List<String> addressesFor(String server) {
        List<String> out = new ArrayList<>();
        out.add(server);
        JsonElement group = loadAliases().get(server);
        if (group != null && group.isJsonArray()) {
            for (JsonElement alias : group.getAsJsonArray()) out.add(normalizeAddress(alias.getAsString()));
        }
        return out;
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
                for (String later : LATER_9B9T) nineB.add(later);
                defaults.add("9b9t", nineB);
                Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
                return defaults;
            }
            JsonObject aliases;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                aliases = JsonParser.parseReader(reader).getAsJsonObject();
            }
            // Files written before an address was a default: add it to the 9b9t group, unless the
            // player already put it somewhere else.
            if (aliases.get("9b9t") instanceof JsonArray nineB) {
                boolean changed = false;
                for (String later : LATER_9B9T) {
                    if (mentions(aliases, later)) continue;
                    nineB.add(later);
                    changed = true;
                    EntityTrackerClient.LOGGER.info("Added {} to the 9b9t group in {}", later, file);
                }
                if (changed) Files.writeString(file, GSON.toJson(aliases), StandardCharsets.UTF_8);
            }
            return aliases;
        } catch (Exception e) {
            // A broken alias file must not stop the bank from opening; it just opens unaliased.
            EntityTrackerClient.LOGGER.error("Could not read {}, ignoring aliases", file, e);
            return new JsonObject();
        }
    }

    private static boolean mentions(JsonObject aliases, String address) {
        for (Map.Entry<String, JsonElement> group : aliases.entrySet()) {
            if (normalizeAddress(group.getKey()).equals(address)) return true;
            if (!group.getValue().isJsonArray()) continue;
            for (JsonElement alias : group.getValue().getAsJsonArray()) {
                if (normalizeAddress(alias.getAsString()).equals(address)) return true;
            }
        }
        return false;
    }

    public record LoadResult(MemoryBank bank, String reason, @Nullable String warning) {}

    public LoadResult load(String server) {
        String key = keyFor(server);
        Path file = bankFile(key);
        readOnly.remove(key);
        if (!Files.exists(file)) return mergeLegacy(key, server);
        try {
            JsonObject json = parse(file);
            int format = json.has("formatVersion") ? json.get("formatVersion").getAsInt() : 1;
            if (format > FORMAT_VERSION) {
                // Written by a newer version of this mod: this one can't read it right, and saving
                // would throw away what it doesn't understand. Leave the file alone.
                readOnly.add(key);
                EntityTrackerClient.LOGGER.warn("Bank {} is format {}, this version reads up to {}; not reading or saving it",
                        file, format, FORMAT_VERSION);
                return new LoadResult(new MemoryBank(key, server), "file from a newer Entity Tracker, left untouched",
                        "Entity Tracker: this server's memory was saved by a newer version of the mod. It was left"
                                + " untouched, and nothing will be remembered until you update Entity Tracker.");
            }
            MemoryBank bank = read(json, key, server, null);
            if (format < FORMAT_VERSION) keepOldFormat(key, file, format, bank);
            return new LoadResult(bank, "loaded existing file", checkVersions(bank, key, file));
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
                    MemoryBank bank = read(parse(backup), key, server, null);
                    checkVersions(bank, key, backup);
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
                        MemoryBank old = read(parse(p), key, server, UUID.fromString(m.group(1)));
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
        checkVersions(merged, key, null);
        merged.markDirty();
        return new LoadResult(merged, "merged old per-account banks " + used + " (old files kept)", null);
    }

    /**
     * Upgrades items saved by an older Minecraft version (see {@link ItemData}) and, when the file
     * holds items from a newer version (the bank was used by a newer game, and this is an older
     * one), keeps a copy of the file and returns a warning. The newer items are left exactly as
     * they are; only reopening a container replaces them with this version's.
     */
    private @Nullable String checkVersions(MemoryBank bank, String key, @Nullable Path file) {
        int upgraded = 0;
        long newer = 0;
        for (ContainerRecord r : bank.allRecords().toList()) {
            upgraded += r.upgradeItems();
            newer += r.newerItems();
            if (r instanceof EntityRecord e && e.trades != null) {
                upgraded += e.trades.upgradeItems();
                newer += e.trades.newerSnapshots();
            }
        }
        if (upgraded > 0) {
            EntityTrackerClient.LOGGER.info("Upgraded {} items saved by an older Minecraft version in bank {}", upgraded, key);
            bank.markDirty();
        }
        if (newer == 0 || file == null) return null;
        Path copy = backupDir(key).resolve("from-newer-versions")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json");
        try {
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
        } catch (IOException e) {
            EntityTrackerClient.LOGGER.error("Could not copy {} to {}", file, copy, e);
        }
        EntityTrackerClient.LOGGER.warn("Bank {} has {} items from a newer Minecraft version (this is data version {}); kept as they are, copy at {}",
                key, newer, ItemData.CURRENT, copy);
        return "Entity Tracker: this memory was last used by a newer Minecraft version. " + newer
                + " remembered items from it are kept unchanged and shown by type only until you open those"
                + " containers again. A copy of the file was saved in backups/" + key + "/from-newer-versions.";
    }

    /**
     * A file in an older format is kept as it was, once (older versions of the mod can read only
     * that copy), and the bank is saved again in the new format.
     */
    private void keepOldFormat(String key, Path file, int format, MemoryBank bank) {
        Path copy = backupDir(key).resolve("before-format-" + FORMAT_VERSION + ".json");
        try {
            if (!Files.exists(copy)) {
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
            bank.markDirty();
            EntityTrackerClient.LOGGER.info("Bank {} is format {}; kept a copy at {} and saving it as format {}",
                    key, format, copy, FORMAT_VERSION);
        } catch (IOException e) {
            // Without the copy, leave the file in its old format rather than risk the only one.
            EntityTrackerClient.LOGGER.error("Could not copy {} to {}; keeping the old format for now", file, copy, e);
        }
    }

    private static JsonObject parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /** {@code legacyOwner}: the account of an old per-account file, for its ownerless ender chest. */
    private static MemoryBank read(JsonObject root, String key, String server, @Nullable UUID legacyOwner) {
        // Books first: their table is referred to from inside the stacks table, each entry once.
        BookStore.unpack(root);
        StackStore.unpack(root);
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
        // The game version that last saved the file; each record and item also says which wrote it.
        root.addProperty("dataVersion", ItemData.CURRENT);
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
        if (readOnly.contains(key)) {
            EntityTrackerClient.LOGGER.warn("Not saving bank {}: its file is from a newer Entity Tracker", key);
            return true; // not a failure to retry
        }
        try {
            Files.createDirectories(file.getParent());
            backupIfNeeded(key, file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                // Here, off the game thread: walking every item for books and stacks is the slow part.
                COMPACT.toJson(StackStore.pack(BookStore.pack(root)), writer);
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
