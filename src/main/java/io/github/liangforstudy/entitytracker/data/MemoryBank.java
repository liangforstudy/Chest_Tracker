package io.github.liangforstudy.entitytracker.data;

import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One server's memory, shared by all of the player's accounts (it is the same world). Only ender
 * chests are per account. Dimensions live inside the records, never in the bank key.
 */
public final class MemoryBank {
    public final String key;
    public final String server;
    /** Accounts seen on this server: UUID -> name, for labelling their ender chests. */
    public final Map<UUID, String> accounts = new LinkedHashMap<>();
    /** The account currently playing, or null before one has joined. */
    public @Nullable UUID currentPlayer;
    private final Map<UUID, EntityRecord> entities = new LinkedHashMap<>();
    private final Map<String, BlockRecord> blocks = new LinkedHashMap<>();
    /** Every block position (both halves of a double chest) -> record key. */
    private final Map<String, String> blockIndex = new HashMap<>();
    /** Keys trashed by the last area action, for undo. */
    private List<String> lastAreaAction = new ArrayList<>();
    private boolean dirty;
    private int quietTicks;

    public MemoryBank(String key, String server) {
        this.key = key;
        this.server = server;
    }

    public @Nullable EntityRecord get(UUID uuid) {
        return entities.get(uuid);
    }

    public EntityRecord getOrCreate(UUID uuid) {
        return entities.computeIfAbsent(uuid, EntityRecord::new);
    }

    /** The block record covering this position, including the second half of a double chest. */
    public @Nullable BlockRecord getBlockAt(String dimension, BlockPos pos) {
        String recordKey = blockIndex.get(BlockRecord.keyFor(dimension, pos));
        return recordKey == null ? null : blocks.get(recordKey);
    }

    public @Nullable BlockRecord getEnderChest(UUID owner) {
        return blocks.get(BlockRecord.enderKey(owner));
    }

    /** For merging old per-account banks: keeps whichever copy was opened (or seen) most recently. */
    void mergeEntity(EntityRecord record) {
        EntityRecord old = entities.get(record.uuid);
        if (old == null || newer(record, old)) entities.put(record.uuid, record);
    }

    void mergeBlock(BlockRecord record) {
        BlockRecord old = blocks.get(record.key());
        if (old == null || newer(record, old)) putBlock(record);
    }

    private static boolean newer(ContainerRecord a, ContainerRecord b) {
        return a.lastOpened != b.lastOpened ? a.lastOpened > b.lastOpened : a.lastSeen > b.lastSeen;
    }

    /** Adds or replaces a block record and re-indexes its positions. */
    public void putBlock(BlockRecord record) {
        BlockRecord old = blocks.put(record.key(), record);
        if (old != null) unindex(old);
        if (!record.global) {
            blockIndex.put(BlockRecord.keyFor(record.dimension, record.pos), record.key());
            for (BlockPos p : record.otherPositions) blockIndex.put(BlockRecord.keyFor(record.dimension, p), record.key());
        }
    }

    /**
     * Drops a block record that has been replaced by another one covering the same blocks (a
     * single chest that became a double chest). Only for that merge; nothing else deletes records.
     */
    public void replaceBlock(BlockRecord old, BlockRecord replacement) {
        if (blocks.remove(old.key()) != null) unindex(old);
        putBlock(replacement);
    }

    private void unindex(BlockRecord record) {
        if (record.global) return;
        blockIndex.remove(BlockRecord.keyFor(record.dimension, record.pos), record.key());
        for (BlockPos p : record.otherPositions) blockIndex.remove(BlockRecord.keyFor(record.dimension, p), record.key());
    }

    public @Nullable ContainerRecord getByKey(String recordKey) {
        BlockRecord block = blocks.get(recordKey);
        if (block != null) return block;
        try {
            return entities.get(UUID.fromString(recordKey));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Permanently forgets a record. Only for "Delete permanently" and for trash older than the
     * trash period, both of which follow an explicit "mark destroyed" by the player.
     */
    public void remove(ContainerRecord record) {
        if (record instanceof EntityRecord e) {
            entities.remove(e.uuid);
        } else if (record instanceof BlockRecord b && blocks.remove(b.key()) != null) {
            unindex(b);
        }
        markDirty();
    }

    public List<String> lastAreaAction() {
        return lastAreaAction;
    }

    public void setLastAreaAction(List<String> keys) {
        lastAreaAction = new ArrayList<>(keys);
        markDirty();
    }

    /** Only for loading from disk. */
    void put(EntityRecord record) {
        entities.put(record.uuid, record);
    }

    public Collection<EntityRecord> all() {
        return Collections.unmodifiableCollection(entities.values());
    }

    public Collection<BlockRecord> allBlocks() {
        return Collections.unmodifiableCollection(blocks.values());
    }

    public Stream<ContainerRecord> allRecords() {
        return Stream.concat(entities.values().stream(), blocks.values().stream());
    }

    public int size() {
        return entities.size() + blocks.size();
    }

    public void markDirty() {
        dirty = true;
        quietTicks = 0;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Counts quiet ticks since the last change; true once the debounce has passed. */
    public boolean tickDebounce(int debounceTicks) {
        if (!dirty) return false;
        return ++quietTicks >= debounceTicks;
    }

    void clearDirty() {
        dirty = false;
        quietTicks = 0;
    }
}
