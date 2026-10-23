package io.github.liangforstudy.entitytracker.search;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

/**
 * Decodes a record's stored items and flattens them, nested shulkers and bundles included, into
 * searchable entries. Cached per record until its snapshot changes.
 */
public final class ItemIndex {
    private static final int MAX_DEPTH = 8;

    /**
     * One item somewhere inside a container. {@code slot} is the container slot it (or the
     * shulker/bundle holding it) occupies; {@code path} lists the shulkers/bundles it sits in.
     * Identical stacks, e.g. duplicated shulkers, stay separate entries and are never merged.
     * {@code words} is searchText split into words once, for close-match search.
     */
    public record IndexedItem(ItemStack stack, int slot, List<String> path, String searchText, String[] words) {}

    private record Cached(long lastOpened, List<IndexedItem> items) {}

    private final Map<String, Cached> cache = new HashMap<>();
    /**
     * Expanded entries per distinct stack (its raw JSON), shared by every copy: a base full of
     * identical shulkers decodes and builds tooltips once, not once per copy. Each copy still
     * gets its own entries with its own slot, so duplicates stay separate results.
     */
    private final Map<JsonElement, List<IndexedItem>> expansions = new HashMap<>();
    private static final int MAX_EXPANSIONS = 50_000;

    /** A record being indexed a few slots at a time: entries so far and the next slot to do. */
    private static final class Partial {
        final long lastOpened;
        final List<IndexedItem> items = new ArrayList<>();
        int next;

        Partial(long lastOpened) {
            this.lastOpened = lastOpened;
        }
    }

    private final Map<String, Partial> partial = new HashMap<>();

    /** All of the record's items, indexing whatever is missing right now (commands, "On me"). */
    public List<IndexedItem> items(ContainerRecord record) {
        return itemsWithin(record, Long.MAX_VALUE);
    }

    /**
     * The record's items, indexing slot by slot until {@code deadline} (System.nanoTime). Returns
     * null if the deadline came first; the next call carries on where this one stopped. One
     * slot (a full shulker) takes about a millisecond, so a big chest never stalls a frame.
     */
    public @Nullable List<IndexedItem> itemsWithin(ContainerRecord record, long deadline) {
        List<IndexedItem> cached = cachedItems(record);
        if (cached != null) return cached;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return List.of();
        if (expansions.size() > MAX_EXPANSIONS) expansions.clear();
        Partial p = partial.get(record.key());
        if (p == null || p.lastOpened != record.lastOpened) {
            p = new Partial(record.lastOpened);
            partial.put(record.key(), p);
        }
        List<ContainerRecord.SlotItem> slots = record.items;
        while (p.next < slots.size()) {
            if (System.nanoTime() >= deadline) return null;
            ContainerRecord.SlotItem slot = slots.get(p.next++);
            for (IndexedItem e : expansions.computeIfAbsent(slot.stack(), json -> expand(json, client))) {
                p.items.add(new IndexedItem(e.stack(), slot.slot(), e.path(), e.searchText(), e.words()));
            }
        }
        partial.remove(record.key());
        cache.put(record.key(), new Cached(record.lastOpened, p.items));
        return p.items;
    }

    /** The record's items if already indexed for its current snapshot, else null (costs nothing). */
    public @Nullable List<IndexedItem> cachedItems(ContainerRecord record) {
        Cached cached = cache.get(record.key());
        return cached != null && cached.lastOpened == record.lastOpened ? cached.items : null;
    }

    /**
     * Indexes records that aren't indexed yet, stopping once {@code budgetNanos} is used up, so
     * the game never stalls on a big memory (a freeze can get you killed). Returns how many
     * records are still left.
     */
    public int warm(Iterable<? extends ContainerRecord> records, long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        int left = 0;
        for (ContainerRecord r : records) {
            if (cachedItems(r) != null) continue;
            if (System.nanoTime() >= end || itemsWithin(r, end) == null) left++;
        }
        return left;
    }

    public void clear() {
        cache.clear();
        partial.clear();
        expansions.clear();
    }

    /** One stack and everything nested in it; slot -1 (filled in per copy). */
    private static List<IndexedItem> expand(JsonElement json, MinecraftClient client) {
        List<IndexedItem> out = new ArrayList<>();
        var ops = client.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        Item.TooltipContext context = Item.TooltipContext.create(client.world);
        // Items that no longer decode (e.g. from a removed mod) are skipped here but stay on disk.
        ItemStack.CODEC.parse(ops, json).result().ifPresent(stack -> add(out, stack, -1, List.of(), context, client, 0));
        return out;
    }

    private static void add(List<IndexedItem> out, ItemStack stack, int slot, List<String> path,
                            Item.TooltipContext context, MinecraftClient client, int depth) {
        String text = searchText(stack, context, client);
        out.add(new IndexedItem(stack, slot, path, text, Fuzzy.words(text)));
        if (depth >= MAX_DEPTH) return;

        List<String> inner = new ArrayList<>(path);
        inner.add(stack.getName().getString());
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            for (ItemStack child : container.iterateNonEmpty()) add(out, child, slot, inner, context, client, depth + 1);
        }
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) {
            for (ItemStack child : bundle.iterate()) add(out, child, slot, inner, context, client, depth + 1);
        }
    }

    /** Whether a stack, or anything inside it (shulker, bundle), matches a lowercase query. */
    public static boolean matchesDeep(ItemStack stack, String query, int depth) {
        if (query.isEmpty()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;
        if (searchText(stack, Item.TooltipContext.create(client.world), client).contains(query)) return true;
        if (depth >= MAX_DEPTH) return false;
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) for (ItemStack c : container.iterateNonEmpty()) if (matchesDeep(c, query, depth + 1)) return true;
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStack c : bundle.iterate()) if (matchesDeep(c, query, depth + 1)) return true;
        return false;
    }

    /** Search text for a single stack (trade offers); empty for an empty stack. */
    public static String searchText(ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (stack.isEmpty() || client.world == null) return "";
        return searchText(stack, Item.TooltipContext.create(client.world), client);
    }

    /** Tooltip lines cover custom name, lore, enchantments and effects; the id covers the rest. */
    private static String searchText(ItemStack stack, Item.TooltipContext context, MinecraftClient client) {
        StringBuilder sb = new StringBuilder(Registries.ITEM.getId(stack.getItem()).toString());
        // The type's own name too, so "shulker box" also finds one renamed "Kit".
        sb.append('\n').append(stack.getItem().getName().getString());
        // Heads: profile name and texture (plus its alias) are searchable even after renaming,
        // as plain words and as head:<name> / headtex:<hash or alias>.
        HeadInfo head = HeadInfo.of(stack);
        if (head != null) {
            if (head.profileName() != null) sb.append('\n').append(head.profileName()).append("\nhead:").append(head.profileName());
            if (head.textureHash() != null) {
                sb.append("\nheadtex:").append(head.textureHash());
                String alias = EntityTrackerClient.headAliases().get(head.textureHash());
                if (alias != null) sb.append('\n').append(alias).append("\nheadtex:").append(alias);
            }
        }
        if (stack.contains(DataComponentTypes.CONTAINER) || stack.contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            // A shulker/bundle tooltip lists its contents, which are indexed as their own entries;
            // match only the container's own name and lore so results aren't doubled.
            sb.append('\n').append(stack.getName().getString());
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore != null) for (Text line : lore.lines()) sb.append('\n').append(line.getString());
            return sb.toString().toLowerCase(Locale.ROOT);
        }
        try {
            for (Text line : stack.getTooltip(context, client.player, TooltipType.BASIC)) {
                sb.append('\n').append(line.getString());
            }
        } catch (RuntimeException e) {
            // A mod's tooltip code failing must not break search; fall back to the name.
            sb.append('\n').append(stack.getName().getString());
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
