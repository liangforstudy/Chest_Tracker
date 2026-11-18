package io.github.liangforstudy.entitytracker.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Several items in one search: "obby, echest" or "obby; echest" is searched as "obby" and
 * "echest", each on its own (aliases and typo tolerance per term), and the results combined.
 */
public final class Terms {
    private Terms() {}

    /** The lowercase query split on commas and semicolons; a plain query gives itself. */
    public static List<String> of(String query) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : query.split("[,;]")) {
            String t = part.trim();
            if (t.equals("@hand") || t.equals("@held")) t = held(); // whatever is in your hand (#15)
            else if (t.startsWith("@")) t = t.substring(1);
            if (t == null) continue;
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? List.of(query) : new ArrayList<>(out);
    }

    /** True for a query starting with @hand / @held, whose @ must be kept for {@link #of}. */
    public static boolean startsWithHeld(String query) {
        return query.startsWith("@hand") || query.startsWith("@held");
    }

    /**
     * What to search for the item in the main hand (else the off hand): a filled map by its id,
     * "[map 12]", so map art is found exactly; anything else by its name. Null with empty hands.
     */
    public static @org.jetbrains.annotations.Nullable String held() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return null;
        net.minecraft.world.item.ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) stack = player.getOffhandItem();
        if (stack.isEmpty()) return null;
        String map = ItemIndex.mapToken(stack);
        if (map != null) return map;
        return stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).replaceAll("[,;]", " ").trim();
    }
}
