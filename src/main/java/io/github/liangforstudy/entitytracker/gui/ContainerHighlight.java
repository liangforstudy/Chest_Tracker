package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.mixin.client.AbstractContainerScreenAccessor;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.search.Terms;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * After picking a search result, the items searched for are outlined in the real container once
 * you open it (shulkers and bundles holding them too), so they don't have to be hunted for again.
 * Lasts a few minutes after the pick; your own inventory's slots are left alone.
 */
public final class ContainerHighlight {
    private static final long LASTS_MS = 5 * 60_000;
    private static final int COLOR = 0xFFFFD24A;
    private static @Nullable List<String> terms;
    private static Set<Item> named = Set.of();
    private static long until;

    private ContainerHighlight() {}

    /** Remembers what was searched for when a result was picked; an empty search clears it. */
    public static void set(String query) {
        if (query == null || query.isBlank()) {
            terms = null;
            return;
        }
        terms = Terms.of(query.trim().toLowerCase(java.util.Locale.ROOT));
        Set<Item> items = new HashSet<>();
        for (String t : terms) items.addAll(TrackerScreen.namedItems(t));
        named = items;
        until = System.currentTimeMillis() + LASTS_MS;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            List<String> active = terms;
            if (active == null || System.currentTimeMillis() > until) return;
            Set<Item> items = named;
            Map<ItemStack, Boolean> cache = new IdentityHashMap<>();
            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                AbstractContainerScreenAccessor pos = (AbstractContainerScreenAccessor) container;
                int left = pos.entitytracker$leftPos(), top = pos.entitytracker$topPos();
                for (Slot slot : container.getMenu().slots) {
                    if (slot.container instanceof Inventory) continue;
                    ItemStack stack = slot.getItem();
                    if (stack.isEmpty()) continue;
                    boolean match = cache.computeIfAbsent(stack, st -> items.contains(st.getItem())
                            || active.stream().anyMatch(t -> ItemIndex.matchesDeep(st, t, 0)));
                    if (!match) continue;
                    int x = left + slot.x - 1, y = top + slot.y - 1;
                    graphics.fill(x, y, x + 18, y + 1, COLOR);
                    graphics.fill(x, y + 17, x + 18, y + 18, COLOR);
                    graphics.fill(x, y + 1, x + 1, y + 17, COLOR);
                    graphics.fill(x + 17, y + 1, x + 18, y + 17, COLOR);
                }
            });
        });
    }
}
