package fr.zo3n.boxed.managers;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.quests.Quest;
import fr.zo3n.boxed.quests.QuestCondition;
import fr.zo3n.boxed.quests.QuestReward;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles loading quest definitions from {@code quests.yml}, tracking per-player
 * progression, and completing quests with reward distribution.
 *
 * <p>Quest definitions are immutable {@link Quest} records. Mutable per-player
 * state lives exclusively in {@link PlayerData}.</p>
 */
public class QuestManager {

    private final BoxedPlugin plugin;

    /** Loaded quest definitions, keyed by quest ID. */
    private final Map<String, Quest> quests = new HashMap<>();

    /**
     * Reverse condition index — built once after loading quests and used by
     * {@link fr.zo3n.boxed.listeners.QuestProgressListener} to skip quests that
     * do not contain the event's condition type.
     *
     * <p>Structure: {@code conditionClass → (questId → [conditionIndices])}</p>
     *
     * <p>Example: if quest {@code "mineur"} has a {@code BreakBlockCondition} at
     * index 0, then {@code conditionIndex.get(BreakBlockCondition.class).get("mineur")}
     * returns {@code [0]}.</p>
     */
    private final Map<Class<? extends QuestCondition>, Map<String, List<Integer>>> conditionIndex
            = new HashMap<>();

    /**
     * Creates a new {@link QuestManager}.
     *
     * @param plugin the owning plugin instance
     */
    public QuestManager(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Loading ──────────────────────────────────────────────────────────────

    /**
     * Loads (or reloads) all quest definitions from {@code quests.yml}.
     * Failed quests are skipped with a warning; valid ones replace the existing map.
     */
    public void loadQuests() {
        quests.clear();

        File file = new File(plugin.getDataFolder(), "quests.yml");
        if (!file.exists()) {
            plugin.saveResource("quests.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("quests");
        if (root == null) {
            plugin.getLogger().warning("No 'quests' section in quests.yml — no quests loaded.");
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                Quest quest = parseQuest(key, section);
                quests.put(key, quest);
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping quest '" + key + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + quests.size() + " quest(s).");
        buildConditionIndex();
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /**
     * Returns an unmodifiable view of all loaded quest definitions.
     *
     * @return immutable map of quest ID → {@link Quest}
     */
    public Map<String, Quest> getQuests() {
        return Collections.unmodifiableMap(quests);
    }

    /**
     * Returns the quest with the given ID, or {@code null} if not found.
     *
     * @param id quest identifier
     * @return the {@link Quest}, or {@code null}
     */
    public Quest getQuest(String id) {
        return quests.get(id);
    }

    /**
     * Returns the condition reverse-index for the given condition type.
     *
     * <p>The returned map is {@code questId → list of condition indices}.
     * Returns an empty map (not {@code null}) when no quest has a condition
     * of the requested type, allowing callers to do an {@code isEmpty()} early-exit
     * without null checks.</p>
     *
     * <p>This map is rebuilt on every {@link #loadQuests()} call and is
     * read-only at runtime — no external mutation.</p>
     *
     * @param type the runtime class of the condition subtype
     * @param <T>  the specific {@link QuestCondition} subtype
     * @return immutable map, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T extends QuestCondition> Map<String, List<Integer>> getConditionIndex(Class<T> type) {
        Map<String, List<Integer>> result = conditionIndex.get(type);
        return result != null ? result : Collections.emptyMap();
    }

    /**
     * Returns all quests belonging to the given progression tier.
     *
     * @param tier tier number (1 = starting tier)
     * @return list of quests in that tier (may be empty)
     */
    public List<Quest> getQuestsForTier(int tier) {
        return quests.values().stream()
                .filter(q -> q.tier() == tier)
                .toList();
    }

    /**
     * Returns {@code true} if all conditions of the quest are fully satisfied
     * by the given player's data.
     *
     * @param data  the player's data
     * @param quest the quest to check
     * @return {@code true} if complete
     */
    public boolean isQuestComplete(PlayerData data, Quest quest) {
        List<QuestCondition> conditions = quest.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            if (data.getConditionProgress(quest.id(), i) < conditions.get(i).amount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if all prerequisites of the quest are completed
     * and the quest itself has not yet been completed.
     *
     * @param data  the player's data
     * @param quest the quest to check
     * @return {@code true} if the quest is now available to the player
     */
    public boolean isQuestAvailable(PlayerData data, Quest quest) {
        if (data.getCompletedQuests().contains(quest.id())) return false;
        if (data.getActiveQuests().contains(quest.id()))    return false;
        return quest.prerequisites().stream()
                .allMatch(prereq -> data.getCompletedQuests().contains(prereq));
    }

    // ─── Progression ──────────────────────────────────────────────────────────

    /**
     * Increments the progress of a specific condition within a quest for a player.
     * If all conditions are satisfied after the increment, the quest is completed.
     *
     * @param player         the player progressing the quest
     * @param data           the player's data (mutated in place)
     * @param questId        identifier of the quest
     * @param conditionIndex index of the condition being advanced
     * @param increment      positive amount to add to the current progress
     * @return {@code true} if this increment caused the quest to be completed
     */
    public boolean incrementProgress(Player player, PlayerData data,
                                     String questId, int conditionIndex, int increment) {
        Quest quest = quests.get(questId);
        if (quest == null) return false;

        QuestCondition cond = quest.conditions().get(conditionIndex);
        int current  = data.getConditionProgress(questId, conditionIndex);
        int capped   = Math.min(current + increment, cond.amount());

        data.setConditionProgress(questId, conditionIndex, quest.conditions().size(), capped);

        if (isQuestComplete(data, quest)) {
            completeQuest(player, data, quest);
            return true;
        }
        return false;
    }

    /**
     * Marks a quest as completed, removes it from active quests, and grants its rewards.
     *
     * @param player the player completing the quest
     * @param data   the player's data (mutated in place)
     * @param quest  the completed quest
     */
    public void completeQuest(Player player, PlayerData data, Quest quest) {
        data.getActiveQuests().remove(quest.id());
        data.getCompletedQuests().add(quest.id());
        data.markDirty();
        grantRewards(player, data, quest);
        plugin.getLogger().info(player.getName() + " completed quest: " + quest.id());
    }

    // ─── Condition index ──────────────────────────────────────────────────────

    /**
     * (Re-)builds the condition reverse-index from the currently loaded quests.
     * Called automatically at the end of {@link #loadQuests()}.
     *
     * <p>Time complexity: O(total conditions across all quests) — runs once at startup
     * and on reload, never in any event-handler hot path.</p>
     */
    private void buildConditionIndex() {
        conditionIndex.clear();
        for (Quest quest : quests.values()) {
            List<QuestCondition> conditions = quest.conditions();
            for (int i = 0; i < conditions.size(); i++) {
                conditionIndex
                        .computeIfAbsent(conditions.get(i).getClass(), k -> new HashMap<>())
                        .computeIfAbsent(quest.id(), k -> new ArrayList<>())
                        .add(i);
            }
        }
    }

    // ─── Reward distribution ─────────────────────────────────────────────────

    /**
     * Distributes all rewards defined on the given quest to the player.
     * Items that do not fit in the inventory are dropped at the player's feet.
     *
     * @param player the reward recipient
     * @param data   the player's data
     * @param quest  the source of the rewards
     */
    private void grantRewards(Player player, PlayerData data, Quest quest) {
        for (QuestReward reward : quest.rewards()) {
            if (reward instanceof QuestReward.XpReward xp) {
                player.giveExp(xp.amount());

            } else if (reward instanceof QuestReward.ItemReward item) {
                ItemStack stack = new ItemStack(item.material(), item.amount());
                player.getInventory().addItem(stack)
                        .forEach((slot, leftover) ->
                                player.getWorld().dropItemNaturally(player.getLocation(), leftover));

            } else if (reward instanceof QuestReward.ZoneExpandReward expand) {
                plugin.getZoneManager().expandZone(player, data, expand.chunks());
            }
        }
    }

    // ─── YAML parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a single quest definition from its YAML {@link ConfigurationSection}.
     *
     * @param id      quest identifier (YAML key)
     * @param section the YAML section for this quest
     * @return a fully constructed {@link Quest} record
     */
    private Quest parseQuest(String id, ConfigurationSection section) {
        String   name         = section.getString("name", id);
        String   description  = section.getString("description", "");
        int      tier         = section.getInt("tier", 1);
        String   iconStr      = section.getString("icon", "BOOK");
        Material icon         = Material.matchMaterial(iconStr != null ? iconStr : "BOOK");
        if (icon == null) icon = Material.BOOK;

        List<QuestCondition> conditions   = parseConditions(section.getMapList("conditions"));
        List<QuestReward>    rewards      = parseRewards(section.getMapList("rewards"));
        List<String>         prerequisites = section.getStringList("prerequisites");

        return new Quest(id, name, description, tier, icon, conditions, rewards, prerequisites);
    }

    /**
     * Parses a list of condition maps from YAML into {@link QuestCondition} instances.
     *
     * @param list raw YAML list of maps
     * @return ordered list of parsed conditions
     */
    private List<QuestCondition> parseConditions(List<Map<?, ?>> list) {
        List<QuestCondition> result = new ArrayList<>();
        for (Map<?, ?> map : list) {
            Object typeObj   = map.get("type");
            Object amountObj = map.get("amount");
            String type   = typeObj   != null ? String.valueOf(typeObj).toUpperCase() : "FISH";
            int    amount = amountObj != null ? toInt(amountObj) : 1;

            QuestCondition cond = switch (type) {
                case "BREAK_BLOCK" -> {
                    Material mat = parseMaterial(map.get("material"), Material.OAK_LOG);
                    yield new QuestCondition.BreakBlockCondition(mat, amount);
                }
                case "PLACE_BLOCK" -> {
                    Material mat = parseMaterial(map.get("material"), Material.STONE);
                    yield new QuestCondition.PlaceBlockCondition(mat, amount);
                }
                case "KILL_MOB" -> {
                    EntityType et = parseEntityType(map.get("entity"), EntityType.ZOMBIE);
                    yield new QuestCondition.KillMobCondition(et, amount);
                }
                case "CRAFT_ITEM" -> {
                    Material mat = parseMaterial(map.get("material"), Material.CRAFTING_TABLE);
                    yield new QuestCondition.CraftItemCondition(mat, amount);
                }
                case "SMELT_ITEM" -> {
                    Material mat = parseMaterial(map.get("material"), Material.IRON_INGOT);
                    yield new QuestCondition.SmeltItemCondition(mat, amount);
                }
                case "FISH"            -> new QuestCondition.FishCondition(amount);
                case "SLEEP"           -> new QuestCondition.SleepCondition(amount);
                case "TRADE_VILLAGER"  -> new QuestCondition.TradeVillagerCondition(amount);
                default -> {
                    plugin.getLogger().warning("Unknown condition type '" + type + "' — skipping.");
                    yield null;
                }
            };

            if (cond != null) result.add(cond);
        }
        return result;
    }

    /**
     * Parses a list of reward maps from YAML into {@link QuestReward} instances.
     *
     * @param list raw YAML list of maps
     * @return ordered list of parsed rewards
     */
    private List<QuestReward> parseRewards(List<Map<?, ?>> list) {
        List<QuestReward> result = new ArrayList<>();
        for (Map<?, ?> map : list) {
            Object typeObj   = map.get("type");
            Object amountObj = map.get("amount");
            String type   = typeObj   != null ? String.valueOf(typeObj).toUpperCase() : "XP";
            int    amount = amountObj != null ? toInt(amountObj) : 0;

            QuestReward reward = switch (type) {
                case "XP"          -> new QuestReward.XpReward(amount);
                case "ITEM" -> {
                    Material mat = parseMaterial(map.get("material"), Material.DIAMOND);
                    yield new QuestReward.ItemReward(mat, amount);
                }
                case "ZONE_EXPAND" -> new QuestReward.ZoneExpandReward(amount);
                default -> {
                    plugin.getLogger().warning("Unknown reward type '" + type + "' — skipping.");
                    yield null;
                }
            };

            if (reward != null) result.add(reward);
        }
        return result;
    }

    // ─── Parsing helpers ──────────────────────────────────────────────────────

    private static int toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(obj)); }
        catch (NumberFormatException e) { return 1; }
    }

    private static Material parseMaterial(Object raw, Material fallback) {
        if (raw == null) return fallback;
        Material mat = Material.matchMaterial(raw.toString());
        return mat != null ? mat : fallback;
    }

    private static EntityType parseEntityType(Object raw, EntityType fallback) {
        if (raw == null) return fallback;
        try { return EntityType.valueOf(raw.toString().toUpperCase()); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
