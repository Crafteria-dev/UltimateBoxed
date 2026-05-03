package fr.zo3n.boxed.quests;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Sealed interface representing a condition that must be satisfied to progress a quest.
 *
 * <p>Each permitted implementation is a Java 17 {@code record} that carries the
 * data specific to its condition type. The sealed hierarchy ensures exhaustive
 * handling in switch expressions and if-instanceof chains.</p>
 *
 * <p>Condition types and their additional fields:</p>
 * <ul>
 *   <li>{@link BreakBlockCondition} – break a given {@link Material} N times</li>
 *   <li>{@link PlaceBlockCondition} – place a given {@link Material} N times</li>
 *   <li>{@link KillMobCondition}   – kill a given {@link EntityType} N times</li>
 *   <li>{@link CraftItemCondition} – craft a given {@link Material} N times</li>
 *   <li>{@link SmeltItemCondition} – extract a given {@link Material} from a furnace N times</li>
 *   <li>{@link FishCondition}      – catch N items while fishing</li>
 *   <li>{@link SleepCondition}     – enter a bed N times</li>
 *   <li>{@link TradeVillagerCondition} – complete a villager trade N times</li>
 * </ul>
 */
public sealed interface QuestCondition
        permits QuestCondition.BreakBlockCondition,
                QuestCondition.PlaceBlockCondition,
                QuestCondition.KillMobCondition,
                QuestCondition.CraftItemCondition,
                QuestCondition.SmeltItemCondition,
                QuestCondition.FishCondition,
                QuestCondition.SleepCondition,
                QuestCondition.TradeVillagerCondition {

    /**
     * Returns the required count to fully satisfy this condition.
     *
     * @return target amount (always &gt; 0)
     */
    int amount();

    /**
     * Returns a human-readable description for display in the GUI / action bar.
     *
     * @return localised description string
     */
    String describe();

    // ─── Permitted implementations ────────────────────────────────────────────

    /** Break a specific block type {@code amount} times. */
    record BreakBlockCondition(Material material, int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Casser " + amount + "x " + material.name();
        }
    }

    /** Place a specific block type {@code amount} times. */
    record PlaceBlockCondition(Material material, int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Poser " + amount + "x " + material.name();
        }
    }

    /** Kill a specific mob type {@code amount} times. */
    record KillMobCondition(EntityType entityType, int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Tuer " + amount + "x " + entityType.name();
        }
    }

    /** Craft a specific item {@code amount} times. */
    record CraftItemCondition(Material material, int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Fabriquer " + amount + "x " + material.name();
        }
    }

    /** Extract a specific smelted material from a furnace {@code amount} times. */
    record SmeltItemCondition(Material material, int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Fondre " + amount + "x " + material.name();
        }
    }

    /** Catch something while fishing {@code amount} times. */
    record FishCondition(int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Pêcher " + amount + " fois";
        }
    }

    /** Enter a bed {@code amount} times (starts sleeping). */
    record SleepCondition(int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Dormir " + amount + " fois";
        }
    }

    /** Complete a trade with a villager {@code amount} times. */
    record TradeVillagerCondition(int amount) implements QuestCondition {
        @Override
        public String describe() {
            return "Échanger avec un villageois " + amount + " fois";
        }
    }
}
