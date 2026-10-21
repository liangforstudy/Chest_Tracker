package io.github.liangforstudy.entitytracker.gui;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.TradeData;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.village.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Shared text and item helpers for the Trades screens (CLAUDE.md 4.11). */
final class TradeView {
    private TradeView() {}

    /** An offer decoded for display, with what changed since the account's previous look. */
    record ShownOffer(TradeData.Offer offer, ItemStack buy1, ItemStack buy2, ItemStack sell,
                      @Nullable String change, int changeColor, String searchText) {}

    /** "Librarian (Expert)", "\"Bob\" (Librarian, Expert)", "Wandering Trader". */
    static String label(EntityRecord r) {
        TradeData t = r.trades;
        String kind = t == null || t.profession == null ? TrackerScreen.label(withoutName(r)) : profession(t.profession);
        String level = t != null && t.profession != null && t.level > 0
                ? Text.translatable("merchant.level." + t.level).getString() : null;
        if (r.name != null) return "\"" + r.name + "\" (" + kind + (level != null ? ", " + level : "") + ")";
        return level != null ? kind + " (" + level + ")" : kind;
    }

    private static EntityRecord withoutName(EntityRecord r) {
        EntityRecord copy = new EntityRecord(r.uuid);
        copy.type = r.type;
        return copy;
    }

    /** The profession's own display name from the registry (modded ones too), else its id. */
    static String profession(String id) {
        MinecraftClient client = MinecraftClient.getInstance();
        Identifier key = Identifier.tryParse(id);
        if (client.world != null && key != null) {
            VillagerProfession p = client.world.getRegistryManager().getOrThrow(RegistryKeys.VILLAGER_PROFESSION).get(key);
            if (p != null) return p.id().getString();
        }
        return key == null ? id : key.getPath();
    }

    /** "on account Alt" when the shown offers are another account's (prices depend on the player). */
    static @Nullable String otherAccount(TradeData trades, TradeData.Snapshot s) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        UUID owner = trades.accountOf(s);
        if (bank == null || owner == null || owner.equals(bank.currentPlayer)) return null;
        String name = bank.accounts.get(owner);
        return Text.translatable("screen.entitytracker.other_account", name != null ? name : owner.toString().substring(0, 8)).getString();
    }

    static List<ShownOffer> offers(EntityRecord r, @Nullable TradeData.Snapshot s) {
        List<ShownOffer> out = new ArrayList<>();
        if (s == null) return out;
        for (TradeData.Offer o : s.offers) {
            ItemStack buy1 = decode(o.buy1()), buy2 = decode(o.buy2()), sell = decode(o.sell());
            String change = null;
            int color = 0xFF9A9A9A;
            if (s.previous != null) {
                TradeData.Offer before = s.previous.stream().filter(p -> same(p, o)).findFirst().orElse(null);
                int was = before == null ? 0 : decode(before.buy1()).getCount();
                if (before == null) {
                    change = Text.translatable("screen.entitytracker.trades.new").getString();
                    color = 0xFF8FE08F;
                } else if (buy1.getCount() > was) {
                    change = Text.translatable("screen.entitytracker.trades.price_up", was).getString();
                    color = 0xFFE8B04A;
                } else if (buy1.getCount() < was) {
                    change = Text.translatable("screen.entitytracker.trades.price_down", was).getString();
                    color = 0xFF8FE08F;
                }
            }
            String text = ItemIndex.searchText(sell) + "\n" + ItemIndex.searchText(buy1)
                    + (buy2.isEmpty() ? "" : "\n" + ItemIndex.searchText(buy2));
            out.add(new ShownOffer(o, buy1, buy2, sell, change, color, text));
        }
        return out;
    }

    /** Offers the account saw last time that are no longer there. */
    static int gone(@Nullable TradeData.Snapshot s) {
        if (s == null || s.previous == null) return 0;
        int n = 0;
        for (TradeData.Offer p : s.previous) if (s.offers.stream().noneMatch(o -> same(p, o))) n++;
        return n;
    }

    /** Same offer (only the price may differ): same goods sold, paid with the same items. */
    private static boolean same(TradeData.Offer a, TradeData.Offer b) {
        return a.sell().equals(b.sell()) && Objects.equals(itemId(a.buy1()), itemId(b.buy1()))
                && Objects.equals(itemId(a.buy2()), itemId(b.buy2()));
    }

    private static @Nullable String itemId(@Nullable JsonElement json) {
        return json != null && json.isJsonObject() && json.getAsJsonObject().has("id") ? json.getAsJsonObject().get("id").getAsString() : null;
    }

    static ItemStack decode(@Nullable JsonElement json) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (json == null || client.world == null) return ItemStack.EMPTY;
        return ItemStack.CODEC.parse(client.world.getRegistryManager().getOps(JsonOps.INSTANCE), json).result().orElse(ItemStack.EMPTY);
    }

    /** "Enchanted Book (Mending)" rather than just "Enchanted Book". */
    static String describe(ItemStack stack) {
        String name = stack.getName().getString();
        ItemEnchantmentsComponent stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return name;
        List<String> enchants = new ArrayList<>();
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : stored.getEnchantmentEntries()) {
            enchants.add(Enchantment.getName(e.getKey(), e.getIntValue()).getString());
        }
        return name + " (" + String.join(", ", enchants) + ")";
    }

    /**
     * A red X over the sold item of an offer that is out of stock. Drawn as two lines rather than
     * the villager screen's sprite, whose grey backing would hide the item.
     */
    static void outOfStockMark(DrawContext context, int x, int y) {
        for (int i = 1; i < 14; i++) {
            context.fill(x + i, y + i, x + i + 2, y + i + 2, 0xFFE03030);
            context.fill(x + 15 - i - 1, y + i, x + 15 - i + 1, y + i + 2, 0xFFE03030);
        }
    }

    /** "12 Emerald + 1 Book" */
    static String price(ShownOffer o) {
        String s = o.buy1().getCount() + " " + o.buy1().getName().getString();
        if (!o.buy2().isEmpty()) s += " + " + o.buy2().getCount() + " " + o.buy2().getName().getString();
        return s;
    }

    static String sold(ShownOffer o) {
        return (o.sell().getCount() > 1 ? o.sell().getCount() + " " : "") + describe(o.sell());
    }

    static String query(String raw) {
        String q = raw.trim().toLowerCase(Locale.ROOT);
        return q.startsWith("@") ? q.substring(1) : q;
    }
}
