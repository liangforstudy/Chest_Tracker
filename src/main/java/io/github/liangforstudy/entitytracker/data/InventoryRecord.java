package io.github.liangforstudy.entitytracker.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;

/**
 * "On <player>": an account's inventory (armour and offhand included).
 * <ul>
 *   <li>The account playing now: {@link #live}, built fresh for each search, so carried items, and
 *   shulkers and bundles in the inventory, are found too.</li>
 *   <li>Every account, as last seen: a stored copy per account in the memory, like ender chests,
 *   so an alt's inventory is searchable from another account ("on account X").</li>
 * </ul>
 */
public final class InventoryRecord extends ContainerRecord {
    public static final String KEY = "on_me";

    /** The account it belongs to. */
    public @Nullable UUID owner;
    /** The inventory of the account playing, right now (not the stored copy). */
    public boolean live;

    private InventoryRecord() {}

    @Override
    public String key() {
        return live || owner == null ? KEY : "inventory:" + owner;
    }

    /** What's kept in the memory: this inventory as of now, no longer live. */
    public InventoryRecord storedCopy() {
        InventoryRecord r = new InventoryRecord();
        r.owner = owner;
        r.type = type;
        r.name = name;
        r.dimension = dimension;
        r.x = x;
        r.y = y;
        r.z = z;
        long now = System.currentTimeMillis();
        r.firstSeen = now;
        r.lastSeen = now;
        r.lastOpened = now;
        r.slotCount = slotCount;
        r.items = new ArrayList<>(items);
        return r;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        writeCommon(o);
        if (owner != null) o.addProperty("owner", owner.toString());
        return o;
    }

    public static InventoryRecord fromJson(JsonObject o) {
        InventoryRecord r = new InventoryRecord();
        r.readCommon(o);
        if (o.has("owner")) r.owner = UUID.fromString(o.get("owner").getAsString());
        return r;
    }

    public static @Nullable InventoryRecord current() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return null;
        var ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        InventoryRecord r = new InventoryRecord();
        r.live = true;
        r.owner = client.player.getUUID();
        r.type = "minecraft:player_head"; // for the icon
        r.name = client.player.getName().getString(); // shown as "On <name>"
        r.dimension = client.level.dimension().identifier().toString();
        r.x = client.player.getX();
        r.y = client.player.getY();
        r.z = client.player.getZ();
        long now = System.currentTimeMillis();
        r.lastSeen = now;
        // A new value each time, so the search cache never reuses an old inventory.
        r.lastOpened = System.nanoTime();
        Inventory inventory = client.player.getInventory();
        r.slotCount = inventory.getContainerSize();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            JsonElement json = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (json != null) r.items.add(new SlotItem(i, json));
        }
        return r;
    }
}
