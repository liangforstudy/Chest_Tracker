package io.github.liangforstudy.entitytracker.track;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.Notes;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.ShelfBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.ShelfBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Things that hold items without a container screen: shelves, decorated pots, jukeboxes,
 * lecterns, chiseled bookshelves, item frames and armor stands. Each is remembered once the
 * player uses it (right-click), like a chest is once opened, and kept up to date from then on
 * with whatever the client can see:
 * <ul>
 *   <li>shelves, decorated pots, item frames and armor stands: the server sends their items, so
 *   they update whenever they're loaded;</li>
 *   <li>jukeboxes and chiseled bookshelves: the server only says whether a slot is filled, so
 *   the item is the one the player put in (taken from their hand once the slot fills up);</li>
 *   <li>lecterns: the book the player put on it, or the one shown when the lectern is opened
 *   (that part is {@link ScreenBinder}'s).</li>
 * </ul>
 * A filled slot whose item was never seen gets the {@link Notes#UNKNOWN_CONTENTS} hint.
 */
public final class DisplayTracker {
    /** Ticks to wait for the server to confirm an item went in, before giving up on it. */
    private static final int CONFIRM_TICKS = 60;
    /** A loaded record unchanged for this long still counts as freshly checked. */
    private static final long RECHECK_MILLIS = 60 * 60 * 1000L;
    /** Armor stand slots in the order they're stored (and shown). */
    private static final EquipmentSlot[] STAND_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private final BankManager banks;
    private final PositionTracker positions;
    private final InteractionTracker clock;
    /** Items the player just put into a jukebox, lectern or chiseled bookshelf, until the slot fills. */
    private final List<Pending> pending = new ArrayList<>();

    private record Pending(String dimension, BlockPos pos, int slot, ItemStack stack, long tick) {}

    public DisplayTracker(BankManager banks, PositionTracker positions, InteractionTracker clock) {
        this.banks = banks;
        this.positions = positions;
        this.clock = clock;
    }

    /** What kind of item holder a block is, by its id; null for anything else. */
    enum Kind {
        /** Items synced to the client: shelves (3) and decorated pots (1). */
        VISIBLE,
        /** Only "has a disc" is synced. */
        JUKEBOX,
        /** Only "has a book" is synced; the book itself shows when it's opened. */
        LECTERN,
        /** Only which of the 6 slots are filled is synced. */
        BOOKSHELF
    }

    /** By class, not id, so a modded block with a similar name never gets its state misread. */
    static @Nullable Kind kind(Block block) {
        if (block instanceof ShelfBlock || block instanceof DecoratedPotBlock) return Kind.VISIBLE;
        if (block instanceof ChiseledBookshelfBlock) return Kind.BOOKSHELF;
        if (block instanceof JukeboxBlock) return Kind.JUKEBOX;
        if (block instanceof LecternBlock) return Kind.LECTERN;
        return null;
    }

    static @Nullable Kind kind(String type) {
        Identifier id = Identifier.tryParse(type);
        return id == null ? null : kind(Registries.BLOCK.get(id));
    }

    /**
     * Holds items but opens no container screen, so a screen opened after clicking it isn't its
     * own (a furnace has 3 slots, like a shelf). Lecterns do open one, for their book.
     */
    public static boolean hasNoScreen(Block block) {
        Kind kind = kind(block);
        return kind != null && kind != Kind.LECTERN;
    }

    /** Item frames (glow ones too) and armor stands. */
    public static boolean isDisplay(String type) {
        return type != null && (type.endsWith("item_frame") || type.endsWith("armor_stand"));
    }

    /** Item frames never move: they're judged at their own spot, not a home area. */
    public static boolean isFixed(String type) {
        return type != null && type.endsWith("item_frame");
    }

    // ---- Right-clicks -------------------------------------------------------------------------

    /** From the interaction mixin, before the click goes to the server. */
    public void onInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        MemoryBank bank = banks.bank();
        ClientWorld world = MinecraftClient.getInstance().world;
        if (bank == null || world == null) return;
        BlockPos pos = hit.getBlockPos();
        BlockState state = world.getBlockState(pos);
        Kind kind = kind(state.getBlock());
        if (kind == null) return;
        // Sneaking with something in either hand uses the item, not the block.
        if (player.isSneaking() && (!player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty())) return;
        ItemStack held = player.getStackInHand(hand);
        String dimension = world.getRegistryKey().getValue().toString();
        BlockRecord record = getOrCreate(bank, world, pos, state);
        switch (kind) {
            case VISIBLE -> readVisible(bank, record, world);
            case JUKEBOX -> {
                if (!state.get(JukeboxBlock.HAS_RECORD) && held.contains(DataComponentTypes.JUKEBOX_PLAYABLE)) {
                    expect(dimension, pos, 0, held);
                }
            }
            case LECTERN -> {
                if (!state.get(LecternBlock.HAS_BOOK) && held.isIn(ItemTags.LECTERN_BOOKS)) expect(dimension, pos, 0, held);
            }
            case BOOKSHELF -> {
                OptionalInt slot = ((ChiseledBookshelfBlock) state.getBlock()).getHitSlot(hit, state.get(ChiseledBookshelfBlock.FACING));
                if (slot.isPresent() && !occupied(state, slot.getAsInt()) && held.isIn(ItemTags.BOOKSHELF_BOOKS)) {
                    expect(dimension, pos, slot.getAsInt(), held);
                }
            }
        }
        bank.markDirty();
    }

    private void expect(String dimension, BlockPos pos, int slot, ItemStack held) {
        pending.removeIf(p -> p.pos.equals(pos) && p.slot == slot && p.dimension.equals(dimension));
        pending.add(new Pending(dimension, pos.toImmutable(), slot, held.copyWithCount(1), clock.currentTick()));
    }

    /** From the interaction mixin: item frames and armor stands are remembered once used. */
    public void onInteractEntity(Entity entity) {
        if (!(entity instanceof ItemFrameEntity) && !(entity instanceof ArmorStandEntity)) return;
        MemoryBank bank = banks.bank();
        ClientWorld world = MinecraftClient.getInstance().world;
        if (bank == null || world == null) return;
        EntityRecord record = bank.getOrCreate(entity.getUuid());
        positions.update(record, entity, world, true);
        readEntity(bank, record, entity, world);
        bank.markDirty();
    }

    // ---- Keeping them up to date --------------------------------------------------------------

    public void tick(MinecraftClient client) {
        MemoryBank bank = banks.bank();
        ClientWorld world = client.world;
        if (bank == null || world == null) {
            pending.clear();
            return;
        }
        confirmPending(bank, world);
        if (clock.currentTick() % Settings.trackIntervalTicks != 0) return;

        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof ItemFrameEntity) && !(entity instanceof ArmorStandEntity)) continue;
            // Just spawned: the item or armour may not have arrived yet; don't read it as empty.
            if (entity.age < 20) continue;
            EntityRecord record = bank.get(entity.getUuid());
            if (record != null && !record.isTrashed()) readEntity(bank, record, entity, world);
        }

        String dimension = world.getRegistryKey().getValue().toString();
        for (BlockRecord record : bank.allBlocks()) {
            if (record.global || record.isTrashed() || !dimension.equals(record.dimension)) continue;
            Kind kind = kind(record.type);
            if (kind == null) continue;
            BlockPos pos = record.pos;
            if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState state = world.getBlockState(pos);
            if (kind(state.getBlock()) != kind) continue; // gone or replaced
            if (kind == Kind.VISIBLE) readVisible(bank, record, world);
            else dropEmptied(bank, record, state, kind);
        }
    }

    /** The slot filled up as expected: the item the player held is what went in. */
    private void confirmPending(MemoryBank bank, ClientWorld world) {
        if (pending.isEmpty()) return;
        String dimension = world.getRegistryKey().getValue().toString();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!p.dimension.equals(dimension) || clock.currentTick() - p.tick > CONFIRM_TICKS) {
                it.remove();
                continue;
            }
            BlockState state = world.getBlockState(p.pos);
            Kind kind = kind(state.getBlock());
            if (kind == null || kind == Kind.VISIBLE || !filled(state, kind, p.slot)) continue;
            it.remove();
            JsonElement json = encode(world, p.stack);
            if (json == null) continue;
            BlockRecord record = getOrCreate(bank, world, p.pos, state);
            List<ContainerRecord.SlotItem> items = new ArrayList<>(record.items);
            items.removeIf(i -> i.slot() == p.slot);
            items.add(new ContainerRecord.SlotItem(p.slot, json));
            items.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
            setItems(bank, record, items, slotCount(kind));
            EntityTrackerClient.LOGGER.debug("{} at {} slot {}: {}", record.type, p.pos.toShortString(), p.slot, p.stack);
        }
    }

    /** Remembered items whose slot is empty now were taken out. */
    private void dropEmptied(MemoryBank bank, BlockRecord record, BlockState state, Kind kind) {
        List<ContainerRecord.SlotItem> items = new ArrayList<>(record.items);
        if (items.removeIf(i -> !filled(state, kind, i.slot()))) setItems(bank, record, items, record.slotCount);
    }

    private void readVisible(MemoryBank bank, BlockRecord record, ClientWorld world) {
        BlockEntity be = world.getBlockEntity(record.pos);
        if (!(be instanceof ShelfBlockEntity) && !(be instanceof DecoratedPotBlockEntity)) return;
        Inventory inventory = (Inventory) be;
        List<ContainerRecord.SlotItem> items = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) add(world, items, i, inventory.getStack(i));
        setChecked(bank, record, items, inventory.size());
    }

    private void readEntity(MemoryBank bank, EntityRecord record, Entity entity, ClientWorld world) {
        List<ContainerRecord.SlotItem> items = new ArrayList<>();
        int slots;
        if (entity instanceof ItemFrameEntity frame) {
            add(world, items, 0, frame.getHeldItemStack());
            slots = 1;
        } else if (entity instanceof ArmorStandEntity stand) {
            for (int i = 0; i < STAND_SLOTS.length; i++) add(world, items, i, stand.getEquippedStack(STAND_SLOTS[i]));
            slots = STAND_SLOTS.length;
        } else {
            return;
        }
        setChecked(bank, record, items, slots);
    }

    /** Contents the client actually sees: saved if they changed, and count as checked either way. */
    private static void setChecked(MemoryBank bank, ContainerRecord record, List<ContainerRecord.SlotItem> items, int slots) {
        if (!items.equals(record.items) || record.slotCount != slots || !record.hasSnapshot()) {
            setItems(bank, record, items, slots);
        } else if (System.currentTimeMillis() - record.lastOpened > RECHECK_MILLIS) {
            // Bumped only now and then: every bump makes search re-index this record.
            record.lastOpened = System.currentTimeMillis();
            bank.markDirty();
        }
    }

    private static void setItems(MemoryBank bank, ContainerRecord record, List<ContainerRecord.SlotItem> items, int slots) {
        record.items = items;
        record.slotCount = slots;
        record.lastOpened = System.currentTimeMillis();
        record.flags.clear();
        bank.markDirty();
    }

    private static void add(ClientWorld world, List<ContainerRecord.SlotItem> items, int slot, ItemStack stack) {
        if (stack.isEmpty()) return;
        JsonElement json = encode(world, stack);
        if (json != null) items.add(new ContainerRecord.SlotItem(slot, json));
    }

    private static @Nullable JsonElement encode(ClientWorld world, ItemStack stack) {
        var ops = world.getRegistryManager().getOps(JsonOps.INSTANCE);
        return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
    }

    private static BlockRecord getOrCreate(MemoryBank bank, ClientWorld world, BlockPos pos, BlockState state) {
        String dimension = world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();
        BlockRecord record = bank.getBlockAt(dimension, pos);
        String type = Registries.BLOCK.getId(state.getBlock()).toString();
        if (record == null || !record.pos.equals(pos)) {
            record = new BlockRecord(false);
            record.firstSeen = now;
            record.dimension = dimension;
            record.pos = pos.toImmutable();
            record.slotCount = slotCount(kind(state.getBlock()));
        }
        record.type = type;
        record.x = pos.getX() + 0.5;
        record.y = pos.getY() + 0.5;
        record.z = pos.getZ() + 0.5;
        record.status = RecordStatus.LOADED;
        record.lastSeen = now;
        bank.putBlock(record);
        return record;
    }

    /** Shelves and pots get theirs from the block entity on the first read. */
    private static int slotCount(@Nullable Kind kind) {
        return kind == Kind.BOOKSHELF ? ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.size() : 1;
    }

    private static boolean occupied(BlockState state, int slot) {
        var slots = ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES;
        return slot >= 0 && slot < slots.size() && state.get(slots.get(slot));
    }

    private static boolean filled(BlockState state, Kind kind, int slot) {
        return switch (kind) {
            case JUKEBOX -> slot == 0 && state.get(JukeboxBlock.HAS_RECORD);
            case LECTERN -> slot == 0 && state.get(LecternBlock.HAS_BOOK);
            case BOOKSHELF -> occupied(state, slot);
            case VISIBLE -> true;
        };
    }

    /**
     * For {@link PositionTracker}'s hints: a slot that's filled but whose item was never seen
     * (put in by someone else, or before this mod was installed).
     */
    static void addHints(BlockRecord record, BlockState state, Set<String> hints) {
        Kind kind = kind(state.getBlock());
        if (kind == null || kind == Kind.VISIBLE) return;
        int slots = kind == Kind.BOOKSHELF ? ChiseledBookshelfBlock.SLOT_OCCUPIED_PROPERTIES.size() : 1;
        for (int i = 0; i < slots; i++) {
            final int slot = i;
            if (filled(state, kind, slot) && record.items.stream().noneMatch(item -> item.slot() == slot)) {
                hints.add(kind == Kind.LECTERN ? Notes.UNREAD_BOOK : Notes.UNKNOWN_CONTENTS);
                return;
            }
        }
    }
}
