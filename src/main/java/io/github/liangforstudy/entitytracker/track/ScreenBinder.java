package io.github.liangforstudy.entitytracker.track;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import io.github.liangforstudy.entitytracker.mixin.client.MountScreenHandlerAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MountScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Binds a container screen to the entity or block it belongs to (CLAUDE.md 4.1 step 2) and
 * snapshots its contents (step 3). Screens that can't be bound with confidence are ignored, which
 * also keeps server menus and plugin GUIs out of memory (4.10).
 */
public final class ScreenBinder {
    private final InteractionTracker interactions;
    private final BankManager banks;
    private final PositionTracker positions;
    /** Screens already looked at, so re-inits (window resize) don't bind twice. */
    private final Map<Screen, Boolean> seen = new WeakHashMap<>();

    public ScreenBinder(InteractionTracker interactions, BankManager banks, PositionTracker positions) {
        this.interactions = interactions;
        this.banks = banks;
        this.positions = positions;
    }

    /** The open screen and the record it fills. {@code networkId} is -1 for blocks. */
    private static final class Bound {
        final ScreenHandler handler;
        final ContainerRecord record;
        final int networkId;
        /** Set once the server has sent the full contents; before that the slots are just empty. */
        boolean synced;

        Bound(ScreenHandler handler, ContainerRecord record, int networkId) {
            this.handler = handler;
            this.record = record;
            this.networkId = networkId;
        }
    }

    private @Nullable Bound current;

    public void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof HandledScreen<?> handled) || seen.containsKey(screen)) return;
            seen.put(screen, Boolean.TRUE);
            MemoryBank bank = banks.bank();
            ClientWorld world = client.world;
            if (bank == null || world == null) return;
            ScreenHandler handler = handled.getScreenHandler();
            Bound bound = bind(client, world, bank, handler, screen.getTitle());
            EntityTrackerClient.LOGGER.debug("Container screen {} ({}) opened, bound to {}", handler.syncId,
                    screen.getClass().getSimpleName(), bound == null ? null : bound.record.key());
            if (bound == null) return;
            bank.markDirty();
            current = bound;
            ScreenEvents.remove(screen).register(closed -> {
                // Closed before the contents arrived (lag): the slots are empty placeholders, so
                // keep the previous snapshot rather than overwriting it with nothing.
                if (bound.synced) {
                    snapshot(client, bound);
                } else {
                    EntityTrackerClient.LOGGER.info("Screen for {} closed before its contents arrived; kept the previous snapshot",
                            bound.record.key());
                }
                if (current == bound) current = null;
            });
        });
    }

    /**
     * Called after the server sends a container's full contents. Snapshotting here as well as on
     * close means a lost close event (lag, another mod swapping screens) can't drop a snapshot.
     */
    public void onInventorySync(int syncId) {
        Bound bound = current;
        if (bound == null || bound.handler.syncId != syncId) return;
        bound.synced = true;
        snapshot(MinecraftClient.getInstance(), bound);
    }

    private @Nullable Bound bind(MinecraftClient client, ClientWorld world, MemoryBank bank, ScreenHandler handler, Text title) {
        // The player's own inventory has 5 crafting slots, the same as a hopper minecart.
        if (handler instanceof PlayerScreenHandler) return null;

        // Horses, donkeys, mules, llamas: the handler holds the entity itself.
        if (handler instanceof MountScreenHandler) {
            Entity mount = ((MountScreenHandlerAccessor) handler).entitytracker$getMount();
            if (!(mount instanceof AbstractDonkeyEntity donkey && donkey.hasChest())) return null;
            interactions.consumeEntity(mount.getUuid());
            return bindEntity(world, bank, handler, mount);
        }

        // Generic screens don't say whose they are. Match recent clicks in order (the server
        // answers them in order); an entity must also match the screen title. No click, no record.
        int containerSlots = containerSlotCount(handler);
        List<InteractionTracker.Pending> fresh = interactions.fresh(Settings.bindTimeoutTicks);
        InteractionTracker.Pending onlySlotMatch = null;
        int slotMatches = 0;
        for (InteractionTracker.Pending p : fresh) {
            if (p.isEntity()) {
                Entity entity = world.getEntityById(p.networkId());
                if (!matches(entity, containerSlots) || !entity.getUuid().equals(p.uuid())) continue;
                slotMatches++;
                onlySlotMatch = p;
                if (title.getString().equals(entity.getDisplayName().getString())) {
                    interactions.consume(p);
                    return bindEntity(world, bank, handler, entity);
                }
            } else {
                Bound bound = bindBlock(world, bank, handler, p.pos(), containerSlots, title);
                if (bound != null) {
                    interactions.consume(p);
                    return bound;
                }
            }
        }
        // No title matched (a server or ViaVersion may show a different title): only safe when a
        // single clicked entity fits the screen at all.
        if (slotMatches == 1) {
            Entity entity = world.getEntityById(onlySlotMatch.networkId());
            EntityTrackerClient.LOGGER.info("Screen title \"{}\" didn't match {}; bound it as the only candidate",
                    title.getString(), entity.getDisplayName().getString());
            interactions.consume(onlySlotMatch);
            return bindEntity(world, bank, handler, entity);
        }
        if (slotMatches > 1) {
            EntityTrackerClient.LOGGER.info("Screen \"{}\" fits {} clicked entities and matches none by name; not recorded",
                    title.getString(), slotMatches);
        }
        return null;
    }

    private Bound bindEntity(ClientWorld world, MemoryBank bank, ScreenHandler handler, Entity entity) {
        EntityRecord record = bank.getOrCreate(entity.getUuid());
        // Record type, name and position now, while the entity is certainly loaded.
        positions.update(record, entity, world, true);
        return new Bound(handler, record, entity.getId());
    }

    private @Nullable Bound bindBlock(ClientWorld world, MemoryBank bank, ScreenHandler handler, BlockPos pos,
                                      int containerSlots, Text title) {
        BlockState state = world.getBlockState(pos);
        String dimension = world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();

        if (state.getBlock() instanceof EnderChestBlock) {
            if (containerSlots != 27) return null;
            if (bank.currentPlayer == null) return null;
            BlockRecord record = bank.getEnderChest(bank.currentPlayer);
            if (record == null) {
                record = new BlockRecord(true);
                record.owner = bank.currentPlayer;
                record.firstSeen = now;
            }
            record.ownerName = bank.accounts.get(bank.currentPlayer);
            fill(record, state, dimension, pos, null, title, now);
            bank.putBlock(record);
            return new Bound(handler, record, -1);
        }

        BlockPos other = null;
        int expected;
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            other = pos.offset(ChestBlock.getFacing(state));
            expected = 54;
        } else if (world.getBlockEntity(pos) instanceof Inventory inventory) {
            expected = inventory.size();
        } else {
            return null;
        }
        if (containerSlots != expected) return null;

        // A double chest is stored at its lower corner so either half finds the same record.
        BlockPos primary = other == null || pos.asLong() < other.asLong() ? pos : other;
        BlockPos second = other == null ? null : (primary.equals(pos) ? other : pos);
        BlockRecord existing = bank.getBlockAt(dimension, primary);
        BlockRecord existingOther = second == null ? null : bank.getBlockAt(dimension, second);
        BlockRecord record;
        if (existing != null && existing.pos.equals(primary)
                && existing.otherPositions.equals(second == null ? List.of() : List.of(second))) {
            record = existing;
        } else {
            // New, or the chest changed shape (single <-> double): start a record for the new shape.
            record = new BlockRecord(false);
            record.firstSeen = existing != null ? existing.firstSeen : now;
            record.dimension = dimension;
            record.pos = primary;
            if (second != null) record.otherPositions.add(second);
            if (existing != null) bank.replaceBlock(existing, record);
            if (existingOther != null && existingOther != existing) bank.replaceBlock(existingOther, record);
        }
        fill(record, state, dimension, primary, second, title, now);
        bank.putBlock(record);
        return new Bound(handler, record, -1);
    }

    private static void fill(BlockRecord record, BlockState state, String dimension, BlockPos pos, @Nullable BlockPos second,
                             Text title, long now) {
        record.type = Registries.BLOCK.getId(state.getBlock()).toString();
        record.dimension = dimension;
        record.pos = pos;
        record.x = pos.getX() + 0.5 + (second == null ? 0 : (second.getX() - pos.getX()) / 2.0);
        record.y = pos.getY() + 0.5;
        record.z = pos.getZ() + 0.5 + (second == null ? 0 : (second.getZ() - pos.getZ()) / 2.0);
        // Default titles ("Chest", "Large Chest") are translatable; a renamed container's title is not.
        record.name = title.getContent() instanceof TranslatableTextContent ? null : title.getString();
        record.status = RecordStatus.LOADED;
        record.lastSeen = now;
    }

    private static boolean matches(@Nullable Entity entity, int containerSlots) {
        return entity instanceof Inventory inventory && !entity.isRemoved() && inventory.size() == containerSlots;
    }

    private static int containerSlotCount(ScreenHandler handler) {
        int count = 0;
        for (Slot slot : handler.slots) if (!(slot.inventory instanceof PlayerInventory)) count++;
        return count;
    }

    private void snapshot(MinecraftClient client, Bound bound) {
        MemoryBank bank = banks.bank();
        ClientWorld world = client.world;
        if (bank == null || world == null) return;
        var ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);

        List<ContainerRecord.SlotItem> items = new ArrayList<>();
        int containerSlots = 0;
        for (Slot slot : bound.handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            int index = containerSlots++;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            var encoded = ItemStack.CODEC.encodeStart(ops, stack);
            JsonElement json = encoded.result().orElse(null);
            if (json == null) {
                EntityTrackerClient.LOGGER.warn("Could not encode {} in slot {}: {}", stack, index,
                        encoded.error().map(Object::toString).orElse("?"));
                continue;
            }
            items.add(new ContainerRecord.SlotItem(index, json));
        }

        ContainerRecord record = bound.record;
        if (record instanceof EntityRecord entityRecord) {
            Entity entity = world.getEntityById(bound.networkId);
            if (entity != null && entity.getUuid().equals(entityRecord.uuid)) positions.update(entityRecord, entity, world);
        }
        record.items = items;
        record.slotCount = containerSlots;
        record.flags.clear(); // a fresh look settles "chest removed", "container changed", portal
        record.lastOpened = System.currentTimeMillis();
        bank.markDirty();
        EntityTrackerClient.LOGGER.debug("Snapshot of {} {}: {} stacks in {} slots", record.type, record.key(),
                items.size(), containerSlots);
    }
}
