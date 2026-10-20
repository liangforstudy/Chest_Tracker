package io.github.liangforstudy.entitytracker.search;

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
     */
    public record IndexedItem(ItemStack stack, int slot, List<String> path, String searchText) {}

    private record Cached(long lastOpened, List<IndexedItem> items) {}

    private final Map<String, Cached> cache = new HashMap<>();

    public List<IndexedItem> items(ContainerRecord record) {
        Cached cached = cache.get(record.key());
        if (cached != null && cached.lastOpened == record.lastOpened) return cached.items;
        List<IndexedItem> items = build(record);
        cache.put(record.key(), new Cached(record.lastOpened, items));
        return items;
    }

    public void clear() {
        cache.clear();
    }

    private static List<IndexedItem> build(ContainerRecord record) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<IndexedItem> out = new ArrayList<>();
        if (client.world == null) return out;
        var ops = client.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        Item.TooltipContext context = Item.TooltipContext.create(client.world);
        for (ContainerRecord.SlotItem slot : record.items) {
            // Items that no longer decode (e.g. from a removed mod) are skipped here but stay on disk.
            ItemStack.CODEC.parse(ops, slot.stack()).result()
                    .ifPresent(stack -> add(out, stack, slot.slot(), List.of(), context, client, 0));
        }
        return out;
    }

    private static void add(List<IndexedItem> out, ItemStack stack, int slot, List<String> path,
                            Item.TooltipContext context, MinecraftClient client, int depth) {
        out.add(new IndexedItem(stack, slot, path, searchText(stack, context, client)));
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

    /** Tooltip lines cover custom name, lore, enchantments and effects; the id covers the rest. */
    private static String searchText(ItemStack stack, Item.TooltipContext context, MinecraftClient client) {
        StringBuilder sb = new StringBuilder(Registries.ITEM.getId(stack.getItem()).toString());
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
