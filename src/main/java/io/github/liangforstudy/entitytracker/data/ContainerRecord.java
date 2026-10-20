package io.github.liangforstudy.entitytracker.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What every remembered container has, whether it is an entity or a block. */
public abstract class ContainerRecord {
    /** Registry id of the entity type or block. */
    public String type;
    public @Nullable String name;
    public String dimension;
    public double x, y, z;
    public RecordStatus status = RecordStatus.LOADED;
    public long firstSeen;
    public long lastSeen;
    /** 0 until the player has opened it. */
    public long lastOpened;
    /** Number of container slots captured at the last snapshot. */
    public int slotCount;
    /** Last snapshot: slot index + item, kept as the item's raw JSON so unknown data survives. */
    public List<SlotItem> items = new ArrayList<>();
    /** When the player marked it destroyed (moved to the trash); 0 = not in the trash. */
    public long trashedAt;
    /** Warning flags (see {@link Notes}); cleared by a fresh snapshot. */
    public Set<String> flags = new LinkedHashSet<>();
    /** Hints that the contents may change on their own (see {@link Notes}). */
    public Set<String> hints = new LinkedHashSet<>();

    public record SlotItem(int slot, JsonElement stack) {}

    /** Unique within a bank; used for caches. */
    public abstract String key();

    public boolean hasSnapshot() {
        return lastOpened != 0;
    }

    public boolean isTrashed() {
        return trashedAt != 0;
    }


    protected void writeCommon(JsonObject o) {
        o.addProperty("type", type);
        if (name != null) o.addProperty("name", name);
        o.addProperty("dimension", dimension);
        JsonArray pos = new JsonArray();
        pos.add(x);
        pos.add(y);
        pos.add(z);
        o.add("pos", pos);
        o.addProperty("status", status.name());
        o.addProperty("firstSeen", firstSeen);
        o.addProperty("lastSeen", lastSeen);
        o.addProperty("lastOpened", lastOpened);
        o.addProperty("slotCount", slotCount);
        JsonArray arr = new JsonArray();
        for (SlotItem item : items) {
            JsonObject s = new JsonObject();
            s.addProperty("slot", item.slot());
            s.add("stack", item.stack());
            arr.add(s);
        }
        o.add("items", arr);
        if (trashedAt != 0) o.addProperty("trashedAt", trashedAt);
        if (!flags.isEmpty()) o.add("flags", toArray(flags));
        if (!hints.isEmpty()) o.add("hints", toArray(hints));
    }

    private static JsonArray toArray(Set<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static void readSet(JsonObject o, String key, Set<String> into) {
        if (o.has(key)) for (JsonElement e : o.getAsJsonArray(key)) into.add(e.getAsString());
    }

    protected void readCommon(JsonObject o) {
        type = o.get("type").getAsString();
        name = o.has("name") ? o.get("name").getAsString() : null;
        dimension = o.get("dimension").getAsString();
        JsonArray pos = o.getAsJsonArray("pos");
        x = pos.get(0).getAsDouble();
        y = pos.get(1).getAsDouble();
        z = pos.get(2).getAsDouble();
        try {
            status = RecordStatus.valueOf(o.get("status").getAsString());
        } catch (IllegalArgumentException e) {
            status = RecordStatus.UNLOADED;
        }
        // Nothing is loaded before the world is; a LOADED status on disk is from the last session.
        if (status == RecordStatus.LOADED) status = RecordStatus.UNLOADED;
        firstSeen = o.get("firstSeen").getAsLong();
        lastSeen = o.get("lastSeen").getAsLong();
        lastOpened = o.get("lastOpened").getAsLong();
        slotCount = o.has("slotCount") ? o.get("slotCount").getAsInt() : 0;
        if (o.has("items")) {
            for (JsonElement e : o.getAsJsonArray("items")) {
                JsonObject s = e.getAsJsonObject();
                items.add(new SlotItem(s.get("slot").getAsInt(), s.get("stack")));
            }
        }
        trashedAt = o.has("trashedAt") ? o.get("trashedAt").getAsLong() : 0;
        readSet(o, "flags", flags);
        readSet(o, "hints", hints);
    }
}
