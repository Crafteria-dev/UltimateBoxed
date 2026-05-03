package fr.zo3n.boxed.quests;

import org.bukkit.Material;

/**
 * Sealed interface representing a reward granted upon quest completion.
 *
 * <p>Three reward types are supported:</p>
 * <ul>
 *   <li>{@link XpReward}         – grants experience points</li>
 *   <li>{@link ItemReward}       – gives items directly to the player's inventory</li>
 *   <li>{@link ZoneExpandReward} – expands the player's zone by N chunks</li>
 * </ul>
 */
public sealed interface QuestReward
        permits QuestReward.XpReward,
                QuestReward.ItemReward,
                QuestReward.ZoneExpandReward {

    /**
     * Returns a human-readable description of this reward for GUI display.
     *
     * @return localised description string
     */
    String describe();

    // ─── Permitted implementations ────────────────────────────────────────────

    /** Grant {@code amount} experience points. */
    record XpReward(int amount) implements QuestReward {
        @Override
        public String describe() {
            return amount + " points d'expérience";
        }
    }

    /** Grant {@code amount} of the given {@link Material} to the player. */
    record ItemReward(Material material, int amount) implements QuestReward {
        @Override
        public String describe() {
            return amount + "x " + material.name();
        }
    }

    /** Expand the player's zone by {@code chunks} chunk(s). */
    record ZoneExpandReward(int chunks) implements QuestReward {
        @Override
        public String describe() {
            return "Déverrouiller +" + chunks + " chunk(s)";
        }
    }
}
