package io.github.liangforstudy.entitytracker.api;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.ItemData;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.gui.TrackerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only access for other mods (Zymbot's freecam looks containers up through reflection, so
 * this mod stays optional for it). Nothing here writes to the memory. Call on the client thread.
 * The method signatures are looked up by name: keep them exactly as they are.
 */
public final class ChestTrackerApi {
    private ChestTrackerApi() {}

    /**
     * What Chest Tracker has recorded for the block container at this position, in the current
     * server's memory bank. Read-only. Main (client) thread.
     *
     * @param dimension "minecraft:overworld" style, as level.dimension().identifier().toString()
     * @return null when nothing is recorded there (or no bank is loaded); otherwise a heading line
     *         (the container's name or type, when it was last opened), then one line per stack,
     *         e.g. "12× Cobblestone", "1× Diamond Pickaxe (Efficiency V)"
     */
    public static @Nullable List<String> describe(String dimension, int x, int y, int z) {
        try {
            MemoryBank bank = EntityTrackerClient.banks() == null ? null : EntityTrackerClient.banks().bank();
            if (bank == null || dimension == null) return null;
            // Covers both halves of a double chest (otherPositions are indexed too).
            return lines(bank.getBlockAt(dimension, new BlockPos(x, y, z)));
        } catch (RuntimeException e) {
            EntityTrackerClient.LOGGER.warn("ChestTrackerApi.describe failed", e);
            return null;
        }
    }

    /**
     * The same for an entity container (chest minecart, donkey, chest boat...) by its UUID, as
     * {@code entity.getUUID().toString()}. Null when nothing is recorded for it.
     */
    public static @Nullable List<String> describeEntity(String uuid) {
        try {
            MemoryBank bank = EntityTrackerClient.banks() == null ? null : EntityTrackerClient.banks().bank();
            if (bank == null || uuid == null) return null;
            EntityRecord record = bank.get(UUID.fromString(uuid));
            return lines(record);
        } catch (RuntimeException e) {
            return null; // not a UUID, or anything else: nothing to show
        }
    }

    private static @Nullable List<String> lines(@Nullable ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        if (r == null || r.isTrashed() || client.level == null) return null;
        List<String> out = new ArrayList<>();
        String heading = TrackerScreen.label(r);
        heading += r.hasSnapshot()
                ? " (last opened " + TrackerScreen.age(r.lastOpened) + " ago)"
                : " (never opened)";
        out.add(heading);
        if (!r.hasSnapshot()) return out;
        DynamicOps<JsonElement> ops = client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        // Same item, same name and enchantments: one line with the total.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ContainerRecord.SlotItem slot : r.items) {
            ItemStack stack;
            try {
                stack = ItemData.decode(ops, slot.stack());
            } catch (RuntimeException e) {
                continue;
            }
            if (stack.isEmpty()) continue;
            counts.merge(describeStack(stack), stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) out.add("(empty)");
        counts.forEach((text, n) -> out.add(n + "× " + text));
        return out;
    }

    /** "Diamond Pickaxe (Efficiency V)", or the custom name: "\"Kit\" (Shulker Box)". */
    private static String describeStack(ItemStack stack) {
        String type = stack.getItem().getName(stack).getString();
        String text = stack.has(DataComponents.CUSTOM_NAME)
                ? "\"" + stack.getHoverName().getString() + "\" (" + type + ")"
                : stack.getHoverName().getString();
        List<String> enchants = new ArrayList<>();
        for (ItemEnchantments e : new ItemEnchantments[] {
                stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
            if (e == null) continue;
            for (var entry : e.entrySet()) {
                enchants.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            }
        }
        return enchants.isEmpty() ? text : text + " (" + String.join(", ", enchants) + ")";
    }
}
