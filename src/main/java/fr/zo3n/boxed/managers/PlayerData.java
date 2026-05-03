package fr.zo3n.boxed.managers;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable runtime representation of a single player's Boxed state.
 *
 * <p>An instance is loaded (or created) when the player joins and persisted to disk
 * by {@link PlayerDataManager}. The object lives in memory for the duration of the
 * player's session and is flushed on quit or plugin disable.</p>
 *
 * <p>Quest progress is stored as a two-level map:
 * {@code questId → int[conditionIndex] = currentProgress}.
 * The array length equals the number of conditions in that quest.</p>
 */
public class PlayerData {

    private final UUID playerId;
    private Location zoneCenter;

    /** Half-side length of the zone in chunks (1 = 1×1 chunk = 16×16 blocks). */
    private int zoneChunks;

    /** Current progression tier (1 = starting). */
    private int currentTier;

    /** IDs of quests the player has fully completed. */
    private final Set<String> completedQuests = new HashSet<>();

    /** IDs of quests currently visible/active for the player. */
    private final Set<String> activeQuests = new HashSet<>();

    /**
     * Per-condition progress map.
     * Key   = quest ID
     * Value = int[] where index == condition index and value == current count
     */
    private final Map<String, int[]> questProgress = new HashMap<>();

    /**
     * Whether the player has completed their first {@code /boxed play} setup.
     * Until this is {@code true}, no zone border is applied and the player
     * remains in the lobby world.
     */
    private boolean worldInitialized = false;

    /**
     * Dirty flag — set whenever any field is mutated, cleared by
     * {@link fr.zo3n.boxed.managers.PlayerDataManager} after a successful disk write.
     * Declared {@code volatile} so that async save threads observe the latest value
     * written by the main thread without acquiring a lock.
     */
    private volatile boolean dirty = false;

    /**
     * Creates a new {@link PlayerData} with default values (tier 1, zone 1 chunk).
     *
     * @param playerId   UUID of the owning player
     * @param zoneCenter centre of the player's starting zone (may be {@code null}
     *                   until the player runs {@code /boxed play} for the first time)
     */
    public PlayerData(UUID playerId, Location zoneCenter) {
        this.playerId   = playerId;
        this.zoneCenter = zoneCenter;
        this.zoneChunks = 1;
        this.currentTier = 1;
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    /** @return UUID of the owning player */
    public UUID getPlayerId() { return playerId; }

    /** @return centre location of the player's zone */
    public Location getZoneCenter() { return zoneCenter; }

    /** @return zone half-size in chunks */
    public int getZoneChunks() { return zoneChunks; }

    /** @return current progression tier */
    public int getCurrentTier() { return currentTier; }

    /** @return mutable set of completed quest IDs */
    public Set<String> getCompletedQuests() { return completedQuests; }

    /** @return mutable set of active quest IDs */
    public Set<String> getActiveQuests() { return activeQuests; }

    /** @return mutable quest progress map */
    public Map<String, int[]> getQuestProgress() { return questProgress; }

    /** @return {@code true} if the player has completed their first {@code /boxed play} */
    public boolean isWorldInitialized() { return worldInitialized; }

    /**
     * Returns {@code true} if this object has been mutated since the last
     * {@link #markClean()} call.  Read by the async save thread — declared
     * {@code volatile} in the field for safe cross-thread visibility.
     */
    public boolean isDirty()  { return dirty; }

    // ─── Setters ──────────────────────────────────────────────────────────────

    /** Updates the centre of this player's zone. */
    public void setZoneCenter(Location zoneCenter) { this.zoneCenter = zoneCenter; dirty = true; }

    /** Updates the zone half-size in chunks. */
    public void setZoneChunks(int zoneChunks) { this.zoneChunks = zoneChunks; dirty = true; }

    /** Updates the current progression tier. */
    public void setCurrentTier(int currentTier) { this.currentTier = currentTier; dirty = true; }

    /** Marks whether the player has completed their first {@code /boxed play} setup. */
    public void setWorldInitialized(boolean worldInitialized) {
        this.worldInitialized = worldInitialized;
        dirty = true;
    }

    /**
     * Marks this object as modified.
     * Must be called by any code that mutates the {@code completedQuests} or
     * {@code activeQuests} sets directly (since those are exposed as mutable references).
     */
    public void markDirty() { dirty = true; }

    /** Clears the dirty flag after the data has been persisted to disk. */
    public void markClean() { dirty = false; }

    // ─── Quest progress helpers ───────────────────────────────────────────────

    /**
     * Returns the current progress for a specific condition inside a quest.
     *
     * @param questId        quest identifier
     * @param conditionIndex index of the condition in the quest's condition list
     * @return current progress value (0 if not yet started)
     */
    public int getConditionProgress(String questId, int conditionIndex) {
        int[] arr = questProgress.get(questId);
        if (arr == null || conditionIndex >= arr.length) return 0;
        return arr[conditionIndex];
    }

    /**
     * Updates the progress for a specific condition inside a quest.
     * Creates the progress array on first access.
     *
     * @param questId        quest identifier
     * @param conditionIndex index of the condition
     * @param conditionCount total number of conditions in the quest (used for array sizing)
     * @param value          new progress value
     */
    public void setConditionProgress(String questId, int conditionIndex,
                                     int conditionCount, int value) {
        questProgress
                .computeIfAbsent(questId, k -> new int[conditionCount])
                [conditionIndex] = value;
        dirty = true;
    }
}
