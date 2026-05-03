package fr.zo3n.boxed.quests;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable record representing a quest definition loaded from {@code quests.yml}.
 *
 * <p>Quest progression (per-player progress, active/completed state) is stored
 * separately in {@link fr.zo3n.boxed.managers.PlayerData} so that this definition
 * can be safely shared across all players without mutable state.</p>
 *
 * <p>Accessors follow the standard Java record convention (no {@code get} prefix):</p>
 * <pre>{@code quest.id()  quest.name()  quest.conditions()  ...}</pre>
 *
 * @param id            unique identifier used in YAML and prerequisite references
 * @param name          display name (supports legacy §-colour codes)
 * @param description   short description shown in the GUI
 * @param tier          progression tier this quest belongs to (1 = starting tier)
 * @param icon          {@link Material} used as the item icon in the GUI
 * @param conditions    ordered list of conditions that must all be satisfied
 * @param rewards       list of rewards granted on completion
 * @param prerequisites IDs of quests that must be completed before this one unlocks
 */
public record Quest(
        String id,
        String name,
        String description,
        int tier,
        Material icon,
        List<QuestCondition> conditions,
        List<QuestReward> rewards,
        List<String> prerequisites
) {
    /**
     * Compact canonical constructor — makes all list fields unmodifiable
     * so callers cannot mutate the quest definition after construction.
     */
    public Quest {
        conditions    = List.copyOf(conditions);
        rewards       = List.copyOf(rewards);
        prerequisites = List.copyOf(prerequisites);
    }
}
