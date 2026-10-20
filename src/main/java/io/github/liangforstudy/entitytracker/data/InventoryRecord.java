package io.github.liangforstudy.entitytracker.data;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * "On me": the player's own inventory (armour and offhand included), built fresh for each search
 * so carried items, and shulkers and bundles in the inventory, are found too. Never stored.
 */
public final class InventoryRecord extends ContainerRecord {
    public static final String KEY = "on_me";

    private InventoryRecord() {}

    @Override
    public String key() {
        return KEY;
    }

    public static @Nullable InventoryRecord current() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return null;
        var ops = client.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        InventoryRecord r = new InventoryRecord();
        r.type = "minecraft:player_head"; // for the icon
        r.dimension = client.world.getRegistryKey().getValue().toString();
        r.x = client.player.getX();
        r.y = client.player.getY();
        r.z = client.player.getZ();
        long now = System.currentTimeMillis();
        r.lastSeen = now;
        // A new value each time, so the search cache never reuses an old inventory.
        r.lastOpened = System.nanoTime();
        PlayerInventory inventory = client.player.getInventory();
        r.slotCount = inventory.size();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            JsonElement json = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (json != null) r.items.add(new SlotItem(i, json));
        }
        return r;
    }
}
