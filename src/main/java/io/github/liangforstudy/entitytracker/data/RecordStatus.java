package io.github.liangforstudy.entitytracker.data;

public enum RecordStatus {
    /** Present in the client world right now. */
    LOADED,
    /** Left the client world in the normal way (walked out of range, relog). */
    UNLOADED,
    /** The whole home area is loaded on the client but the entity is not there. */
    MISSING,
    /** Disappeared close to the player with item drops, or died. Kept, never deleted automatically. */
    LIKELY_DESTROYED
}
