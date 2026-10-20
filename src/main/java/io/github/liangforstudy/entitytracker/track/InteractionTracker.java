package io.github.liangforstudy.entitytracker.track;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Recent right-clicks on entities and blocks (CLAUDE.md 4.1 step 1, 4.1d A), fed by the
 * interaction mixins. Kept as a queue, not just the last click: under lag, cart 1's screen can
 * arrive after the player has already clicked cart 2, and the server answers clicks in order.
 */
public final class InteractionTracker {
    private static final int MAX_PENDING = 8;

    /** Either an entity click (uuid != null) or a block click (pos != null). */
    public record Pending(@Nullable UUID uuid, int networkId, @Nullable BlockPos pos, long tick) {
        public boolean isEntity() {
            return uuid != null;
        }
    }

    private final Deque<Pending> queue = new ArrayDeque<>();
    private long tick;

    public void set(Entity entity) {
        add(new Pending(entity.getUuid(), entity.getId(), null, tick));
    }

    public void setBlock(BlockPos pos) {
        add(new Pending(null, -1, pos.toImmutable(), tick));
    }

    private void add(Pending p) {
        queue.addLast(p);
        while (queue.size() > MAX_PENDING) queue.removeFirst();
    }

    public void tick() {
        tick++;
    }

    public long currentTick() {
        return tick;
    }

    /** Clicks still fresh enough to bind a screen to, oldest first. */
    public List<Pending> fresh(int maxAgeTicks) {
        queue.removeIf(p -> tick - p.tick() > maxAgeTicks);
        return new ArrayList<>(queue);
    }

    /** A screen was bound to this click: it and every older click are done. */
    public void consume(Pending used) {
        while (!queue.isEmpty()) {
            if (queue.removeFirst() == used) return;
        }
    }

    /** A mount screen opened for this entity: drop its click and older ones, if it was clicked. */
    public void consumeEntity(UUID uuid) {
        for (Pending p : queue) {
            if (uuid.equals(p.uuid())) {
                consume(p);
                return;
            }
        }
    }

    public void clear() {
        queue.clear();
    }
}
