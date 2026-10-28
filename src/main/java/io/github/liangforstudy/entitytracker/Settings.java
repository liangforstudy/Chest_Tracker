package io.github.liangforstudy.entitytracker;

/**
 * Tunables. Plain constants for now; they move to a config file once there is a config screen.
 */
public final class Settings {
    /**
     * How long after interacting with an entity a container screen may still be bound to it.
     * CLAUDE.md suggests ~10 ticks; 9b9t ping can exceed a second, so this is wider. Binding
     * also requires the slot count to match the entity's inventory, which keeps it safe.
     */
    public static int bindTimeoutTicks = 60;
    /** The longest a click waits for its screen when the server is very laggy (see InteractionTracker#windowTicks). */
    public static int bindTimeoutMaxTicks = 400;
    /** Home area radius in chunks (1 = the 3x3 area around homeChunk). */
    public static int homeRadiusChunks = 1;
    /** An entity vanishing closer than this, followed by item drops, counts as likely destroyed. */
    public static double destroyDistance = 16.0;
    /** Ticks to wait for item drops after a nearby entity disappears. */
    public static int dropWindowTicks = 20;
    /** Ticks between position-tracking passes. */
    public static int trackIntervalTicks = 20;
    /** Ticks of quiet after a change before the bank is written. */
    public static int saveDebounceTicks = 100;
    /** Days without opening after which an entry counts as stale. */
    public static int staleDays = 7;
    /**
     * "Missing" is only judged within this distance of the entity's last position. Servers only
     * send entities within their tracking range (often 48-96 blocks), which is less than the
     * view distance, so farther away a loaded home area proves nothing.
     */
    public static double missingCheckRange = 48.0;
    /**
     * The same for item frames and armor stands, which servers often send only within 32 blocks
     * (Spigot and Paper count them as "misc" entities).
     */
    public static double displayMissingCheckRange = 24.0;
    /** Radius around a vanished entity in which dropped items count as its drops. */
    public static double dropRadius = 2.5;
    /** Days a record stays in the trash (restorable) before it is forgotten. */
    public static int trashDays = 7;
    /** Remember villager and wandering trader offers (CLAUDE.md 4.11). */
    public static boolean trackTrades = true;
    /**
     * How long each page of a villager's offer preview shows before the next two, in ticks
     * (35 = 1.75 s). Stored as ticks; a settings screen may show it in seconds (1-2 s).
     */
    public static int tradePreviewTicks = 35;
    /** Time per client tick spent building the search index in the background. */
    public static long indexBudgetNanos = 1_000_000;
    /**
     * Time the search may take per frame (searching, and indexing what isn't ready yet); the
     * rest follows on the next frames, nearest containers first, so results show up at once.
     */
    public static long searchBudgetNanos = 4_000_000;
    /** Daily backups to keep per bank. */
    public static int backupsToKeep = 7;

    private Settings() {}
}
