package io.github.liangforstudy.entitytracker.track;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Remembers the last entity or block the player right-clicked (CLAUDE.md 4.1 step 1), fed by
 * ClientPlayerInteractionManagerMixin. Only the most recent one counts, so a screen is never
 * attributed to something clicked before it.
 */
public final class InteractionTracker {
    public record PendingTarget(UUID uuid, int networkId, long tick) {}

    public record PendingBlock(BlockPos pos, long tick) {}

    private @Nullable PendingTarget pending;
    private @Nullable PendingBlock pendingBlock;
    private long tick;

    public void set(Entity entity) {
        pending = new PendingTarget(entity.getUuid(), entity.getId(), tick);
        pendingBlock = null;
    }

    public void setBlock(BlockPos pos) {
        pendingBlock = new PendingBlock(pos.toImmutable(), tick);
        pending = null;
    }

    public void tick() {
        tick++;
    }

    public long currentTick() {
        return tick;
    }

    /** The pending entity if it is still fresh enough to bind a screen to. */
    public @Nullable PendingTarget fresh(int maxAgeTicks) {
        if (pending == null || tick - pending.tick() > maxAgeTicks) return null;
        return pending;
    }

    /** The pending block if it is still fresh enough to bind a screen to. */
    public @Nullable PendingBlock freshBlock(int maxAgeTicks) {
        if (pendingBlock == null || tick - pendingBlock.tick() > maxAgeTicks) return null;
        return pendingBlock;
    }

    public void clear() {
        pending = null;
        pendingBlock = null;
    }
}
