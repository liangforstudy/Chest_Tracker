package io.github.liangforstudy.entitytracker.data;

/**
 * Warning flags and hints a record can carry (CLAUDE.md 4.1d). Stored as strings so a newer
 * version's notes survive in an older one's save file.
 */
public final class Notes {
    // Flags: something about the container changed; cleared when it is opened again.
    public static final String CHEST_REMOVED = "chest_removed";
    public static final String CONTAINER_CHANGED = "container_changed";
    public static final String WENT_THROUGH_PORTAL = "went_through_portal";

    // Hints: the contents may change on their own; recomputed while the container is loaded.
    public static final String HOPPER_BELOW = "hopper_below";
    public static final String FED_BY_HOPPER = "fed_by_hopper";
    public static final String PROCESSES_ITEMS = "processes_items";
    public static final String MOVES_ITEMS = "moves_items";
    public static final String OVER_HOPPER = "over_hopper";

    private Notes() {}
}
