package io.github.liangforstudy.entitytracker.track;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Follows remembered entities while they are loaded (CLAUDE.md 4.1 step 4), recognises them on
 * sight (step 7), and decides what a disappearance means (step 5). Never deletes a record.
 */
public final class PositionTracker {
    private final BankManager banks;
    private final InteractionTracker clock;
    /** Remembered entities that just vanished near the player, waiting to see if items drop. */
    private final List<Vanished> vanished = new ArrayList<>();

    private record Vanished(UUID uuid, Vec3d pos, long tick) {}

    public PositionTracker(BankManager banks, InteractionTracker clock) {
        this.banks = banks;
        this.clock = clock;
    }

    public void register() {
        ClientEntityEvents.ENTITY_LOAD.register(this::onLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(this::onUnload);
    }

    /**
     * Refreshes a record from the live entity, re-homing it if it left its home area. Returns
     * true when something worth saving changed (lastSeen alone is not).
     */
    public boolean update(EntityRecord record, Entity entity, ClientWorld world) {
        return update(record, entity, world, false);
    }

    /**
     * @param reappearing true when the entity has just come into view or been opened. Only then
     *                    does a trashed record count as seen again; a trashed entity that never
     *                    left view stays in the trash.
     */
    public boolean update(EntityRecord record, Entity entity, ClientWorld world, boolean reappearing) {
        long now = System.currentTimeMillis();
        String dimension = world.getRegistryKey().getValue().toString();
        ChunkPos chunk = entity.getChunkPos();
        boolean isNew = record.type == null;
        String name = entity.hasCustomName() ? entity.getCustomName().getString() : null;
        // Right after spawning, the custom name hasn't arrived from the server yet; keep the old one.
        if (name == null && reappearing) name = record.name;
        boolean changed = isNew || record.status != RecordStatus.LOADED || !dimension.equals(record.dimension)
                || !Objects.equals(name, record.name)
                || Math.abs(entity.getX() - record.x) + Math.abs(entity.getY() - record.y) + Math.abs(entity.getZ() - record.z) >= 1.0;

        record.type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        record.name = name;
        if (isNew) {
            record.firstSeen = now;
            record.homeChunkX = chunk.x;
            record.homeChunkZ = chunk.z;
        } else if (!dimension.equals(record.dimension) || !isHome(record, chunk)) {
            EntityTrackerClient.LOGGER.info("Re-homed {} {} from chunk {},{} ({}) to {},{} ({})", record.type,
                    record.uuid, record.homeChunkX, record.homeChunkZ, record.dimension, chunk.x, chunk.z, dimension);
            record.homeChunkX = chunk.x;
            record.homeChunkZ = chunk.z;
            record.timesRehomed++;
        }
        if (record.status == RecordStatus.LIKELY_DESTROYED) record.reappeared = true;
        if (reappearing && record.isTrashed()) {
            // Marked destroyed but seen alive: it wasn't destroyed after all (CLAUDE.md 4.1 step 5).
            record.trashedAt = 0;
            record.reappeared = true;
            changed = true;
            EntityTrackerClient.LOGGER.info("{} {} reappeared, restored from trash", record.type, record.uuid);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("screen.entitytracker.reappeared",
                        name != null ? "\"" + name + "\"" : entity.getType().getName().getString()), false);
            }
        }
        record.dimension = dimension;
        record.x = entity.getX();
        record.y = entity.getY();
        record.z = entity.getZ();
        record.status = RecordStatus.LOADED;
        record.lastSeen = now;
        return changed;
    }

    private static boolean isHome(EntityRecord record, ChunkPos chunk) {
        int r = Settings.homeRadiusChunks;
        return Math.abs(chunk.x - record.homeChunkX) <= r && Math.abs(chunk.z - record.homeChunkZ) <= r;
    }

    private void onLoad(Entity entity, ClientWorld world) {
        MemoryBank bank = banks.bank();
        if (bank == null) return;
        if (entity instanceof ItemEntity) {
            // Items appearing where a remembered entity just vanished: it was most likely broken.
            Vec3d pos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
            for (Iterator<Vanished> it = vanished.iterator(); it.hasNext(); ) {
                Vanished v = it.next();
                if (v.pos.squaredDistanceTo(pos) > 4.0) continue;
                EntityRecord record = bank.get(v.uuid);
                if (record != null && record.status != RecordStatus.LOADED) {
                    record.status = RecordStatus.LIKELY_DESTROYED;
                    bank.markDirty();
                    EntityTrackerClient.LOGGER.info("{} {} likely destroyed (items dropped)", record.type, record.uuid);
                }
                it.remove();
            }
            return;
        }
        EntityRecord record = bank.get(entity.getUuid());
        if (record == null) return;
        // Recognised on sight, before opening (CLAUDE.md test 22).
        EntityTrackerClient.LOGGER.info("Recognised {} {}{}", record.type, record.uuid,
                record.name != null ? " \"" + record.name + "\"" : "");
        update(record, entity, world, true);
        bank.markDirty();
    }

    private void onUnload(Entity entity, ClientWorld world) {
        MemoryBank bank = banks.bank();
        if (bank == null) return;
        EntityRecord record = bank.get(entity.getUuid());
        if (record == null) return;
        update(record, entity, world);
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity instanceof LivingEntity living && living.isDead()) {
            record.status = RecordStatus.LIKELY_DESTROYED;
            EntityTrackerClient.LOGGER.info("{} {} died", record.type, record.uuid);
        } else {
            record.status = RecordStatus.UNLOADED;
            if (client.player != null && client.player.squaredDistanceTo(entity) < Settings.destroyDistance * Settings.destroyDistance) {
                vanished.add(new Vanished(record.uuid, new Vec3d(entity.getX(), entity.getY(), entity.getZ()), clock.currentTick()));
            }
        }
        bank.markDirty();
    }

    /** Runs every tick; does a full pass every {@link Settings#trackIntervalTicks}. */
    public void tick(MinecraftClient client) {
        vanished.removeIf(v -> clock.currentTick() - v.tick > Settings.dropWindowTicks);
        MemoryBank bank = banks.bank();
        ClientWorld world = client.world;
        if (bank == null || world == null || clock.currentTick() % Settings.trackIntervalTicks != 0) return;

        Set<UUID> present = new HashSet<>();
        for (Entity entity : world.getEntities()) {
            EntityRecord record = bank.get(entity.getUuid());
            if (record == null) continue;
            present.add(record.uuid);
            if (update(record, entity, world)) bank.markDirty();
        }

        if (clock.currentTick() % (Settings.trackIntervalTicks * 5L) == 0) checkBlocks(world, bank);

        // Home area fully loaded but the entity isn't there: missing (not destroyed).
        String dimension = world.getRegistryKey().getValue().toString();
        for (EntityRecord record : bank.all()) {
            if (present.contains(record.uuid) || !dimension.equals(record.dimension)) continue;
            if (record.status != RecordStatus.UNLOADED && record.status != RecordStatus.LOADED) continue;
            if (homeAreaLoaded(world, record)) {
                record.status = RecordStatus.MISSING;
                bank.markDirty();
                EntityTrackerClient.LOGGER.info("{} {} missing from its home area", record.type, record.uuid);
            }
        }
    }

    /**
     * Block containers: present while their chunk is loaded and a container is still there;
     * gone (likely destroyed) if the chunk is loaded and it isn't. Checks "is a container", not
     * the exact block id, since copper chests change id as they oxidise.
     */
    private void checkBlocks(ClientWorld world, MemoryBank bank) {
        String dimension = world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();
        for (BlockRecord record : bank.allBlocks()) {
            if (record.global || !dimension.equals(record.dimension)) continue;
            BlockPos pos = record.pos;
            if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                if (record.status == RecordStatus.LOADED) {
                    record.status = RecordStatus.UNLOADED;
                    bank.markDirty();
                }
                continue;
            }
            boolean present = world.getBlockEntity(pos) instanceof Inventory
                    || world.getBlockState(pos).getBlock() instanceof EnderChestBlock;
            RecordStatus next = present ? RecordStatus.LOADED : RecordStatus.LIKELY_DESTROYED;
            if (present) record.lastSeen = now;
            if (record.status != next) {
                if (next == RecordStatus.LIKELY_DESTROYED) {
                    EntityTrackerClient.LOGGER.info("{} at {} is gone", record.type, pos.toShortString());
                }
                record.status = next;
                bank.markDirty();
            }
        }
    }

    private static boolean homeAreaLoaded(ClientWorld world, EntityRecord record) {
        int r = Settings.homeRadiusChunks;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!world.getChunkManager().isChunkLoaded(record.homeChunkX + dx, record.homeChunkZ + dz)) return false;
            }
        }
        return true;
    }
}
