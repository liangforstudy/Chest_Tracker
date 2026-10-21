package io.github.liangforstudy.entitytracker.track;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.TradeData;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerDataContainer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Binds a merchant screen to the villager or wandering trader the player just clicked and keeps
 * its offers (CLAUDE.md 4.11). Same rules as containers: no matching click, no record.
 */
public final class TradeBinder {
    private final InteractionTracker interactions;
    private final BankManager banks;
    private final PositionTracker positions;
    private final Map<Screen, Boolean> seen = new WeakHashMap<>();

    private static final class Bound {
        final MerchantScreenHandler handler;
        final EntityRecord record;
        final UUID account;
        /** What this account saw before this opening, for the change marks. */
        final @Nullable List<TradeData.Offer> previous;
        /** Set once the offers have arrived; closing before that keeps the old ones. */
        boolean synced;

        Bound(MerchantScreenHandler handler, EntityRecord record, UUID account, @Nullable List<TradeData.Offer> previous) {
            this.handler = handler;
            this.record = record;
            this.account = account;
            this.previous = previous;
        }
    }

    private @Nullable Bound current;

    public TradeBinder(InteractionTracker interactions, BankManager banks, PositionTracker positions) {
        this.interactions = interactions;
        this.banks = banks;
        this.positions = positions;
    }

    public void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof MerchantScreen merchantScreen) || seen.containsKey(screen)) return;
            seen.put(screen, Boolean.TRUE);
            MemoryBank bank = banks.bank();
            ClientWorld world = client.world;
            if (!Settings.trackTrades || bank == null || world == null || bank.currentPlayer == null) return;
            MerchantScreenHandler handler = merchantScreen.getScreenHandler();
            Entity merchant = find(world, screen.getTitle());
            EntityTrackerClient.LOGGER.debug("Merchant screen {} opened, bound to {}", handler.syncId,
                    merchant == null ? null : merchant.getUuid());
            if (merchant == null) return;

            EntityRecord record = bank.getOrCreate(merchant.getUuid());
            if (record.trades == null) record.trades = new TradeData();
            positions.update(record, merchant, world, true);
            readVillager(record.trades, merchant);
            TradeData.Snapshot old = record.trades.byAccount.get(bank.currentPlayer);
            Bound bound = new Bound(handler, record, bank.currentPlayer, old == null ? null : old.offers);
            current = bound;
            bank.markDirty();
            ScreenEvents.remove(screen).register(closed -> {
                // Uses go up while trading; the client's copy of the offers follows them.
                if (bound.synced) snapshot(bound, handler.getRecipes());
                if (current == bound) current = null;
            });
        });
    }

    /** The offers arrived (right after the screen opened, and again after a restock or level-up). */
    public void onOffers(int syncId, List<TradeOffer> offers) {
        Bound bound = current;
        if (bound == null || bound.handler.syncId != syncId) return;
        bound.synced = true;
        snapshot(bound, offers);
    }

    /**
     * The oldest fresh click on a merchant whose name is the screen title, else the only clicked
     * merchant. Several clicked and none named like the screen: not recorded.
     */
    private @Nullable Entity find(ClientWorld world, Text title) {
        InteractionTracker.Pending only = null;
        int candidates = 0;
        for (InteractionTracker.Pending p : interactions.fresh(Settings.bindTimeoutTicks)) {
            if (!p.isEntity()) continue;
            Entity entity = world.getEntityById(p.networkId());
            if (!(entity instanceof MerchantEntity) || entity.isRemoved() || !entity.getUuid().equals(p.uuid())) continue;
            if (title.getString().equals(entity.getDisplayName().getString())) {
                interactions.consume(p);
                return entity;
            }
            candidates++;
            only = p;
        }
        if (candidates == 1) {
            interactions.consume(only);
            return world.getEntityById(only.networkId());
        }
        return null;
    }

    /** Profession and level as the client sees them; wandering traders have neither. */
    static void readVillager(TradeData trades, Entity entity) {
        if (entity instanceof VillagerDataContainer container) {
            VillagerData data = container.getVillagerData();
            trades.profession = data.profession().getKey().map(k -> k.getValue().toString()).orElse(null);
            trades.villagerType = data.type().getKey().map(k -> k.getValue().toString()).orElse(null);
            trades.level = data.level();
        } else {
            trades.profession = null;
            trades.villagerType = null;
            trades.level = 0;
        }
    }

    private void snapshot(Bound bound, List<TradeOffer> offers) {
        MinecraftClient client = MinecraftClient.getInstance();
        MemoryBank bank = banks.bank();
        if (bank == null || client.world == null) return;
        RegistryOps<JsonElement> ops = client.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        List<TradeData.Offer> out = new ArrayList<>();
        for (TradeOffer offer : offers) {
            JsonElement buy1 = encode(ops, offer.getDisplayedFirstBuyItem());
            JsonElement sell = encode(ops, offer.getSellItem());
            if (buy1 == null || sell == null) continue;
            ItemStack second = offer.getDisplayedSecondBuyItem();
            out.add(new TradeData.Offer(buy1, offer.getOriginalFirstBuyItem().getCount(),
                    second.isEmpty() ? null : encode(ops, second), sell,
                    offer.getUses(), offer.getMaxUses(), offer.getMerchantExperience()));
        }
        TradeData trades = bound.record.trades;
        TradeData.Snapshot s = new TradeData.Snapshot();
        s.time = System.currentTimeMillis();
        s.level = trades.level;
        s.offers = out;
        s.previous = bound.previous;
        trades.byAccount.put(bound.account, s);
        bound.record.lastOpened = s.time;
        bound.record.flags.clear(); // a fresh look settles "levelled up", "profession changed"
        bank.markDirty();
        EntityTrackerClient.LOGGER.debug("Offers of {} {}: {}", bound.record.type, bound.record.key(), out.size());
    }

    private static @Nullable JsonElement encode(RegistryOps<JsonElement> ops, ItemStack stack) {
        if (stack.isEmpty()) return null;
        return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
    }
}
