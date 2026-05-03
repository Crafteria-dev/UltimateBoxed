package fr.zo3n.boxed.listeners;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.managers.PlayerData;
import fr.zo3n.boxed.quests.Quest;
import fr.zo3n.boxed.quests.QuestCondition;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Generic listener that handles all quest-condition progress events.
 *
 * <p>A single private method {@link #processCondition} dispatches to the correct
 * quest condition via a {@link Class} token and a typed {@link Predicate}, keeping
 * each per-event handler minimal and avoiding repetitive per-type boilerplate.</p>
 *
 * <p>Event handlers are marked {@code MONITOR / ignoreCancelled=true} so that only
 * confirmed, successful actions count toward quest progress.</p>
 *
 * <p>No I/O or blocking operations are performed on the main thread.
 * Persistence is scheduled asynchronously via {@link fr.zo3n.boxed.managers.PlayerDataManager}.</p>
 */
public class QuestProgressListener implements Listener {

    private final BoxedPlugin plugin;

    /**
     * Creates a new {@link QuestProgressListener}.
     *
     * @param plugin the owning plugin instance
     */
    public QuestProgressListener(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Block events ─────────────────────────────────────────────────────────

    /**
     * Handles block-break progress for {@link QuestCondition.BreakBlockCondition}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material broken = event.getBlock().getType();
        processCondition(event.getPlayer(),
                QuestCondition.BreakBlockCondition.class,
                cond -> cond.material() == broken, 1);
    }

    /**
     * Handles block-place progress for {@link QuestCondition.PlaceBlockCondition}.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Material placed = event.getBlock().getType();
        processCondition(event.getPlayer(),
                QuestCondition.PlaceBlockCondition.class,
                cond -> cond.material() == placed, 1);
    }

    // ─── Entity events ────────────────────────────────────────────────────────

    /**
     * Handles mob-kill progress for {@link QuestCondition.KillMobCondition}.
     * Only credits the player who landed the killing blow.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity().getKiller() instanceof Player killer)) return;
        var entityType = event.getEntity().getType();
        processCondition(killer,
                QuestCondition.KillMobCondition.class,
                cond -> cond.entityType() == entityType, 1);
    }

    // ─── Crafting / Smelting ──────────────────────────────────────────────────

    /**
     * Handles crafting progress for {@link QuestCondition.CraftItemCondition}.
     * Correctly accounts for shift-click crafting (multiple items at once).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() == Material.AIR) return;

        Material crafted = result.getType();
        int amount = event.isShiftClick() ? result.getAmount() : 1;

        processCondition(player,
                QuestCondition.CraftItemCondition.class,
                cond -> cond.material() == crafted, amount);
    }

    /**
     * Handles furnace-extraction progress for {@link QuestCondition.SmeltItemCondition}.
     * {@code event.getItemType()} returns the result material (e.g. IRON_INGOT).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        Material smelted = event.getItemType();
        processCondition(event.getPlayer(),
                QuestCondition.SmeltItemCondition.class,
                cond -> cond.material() == smelted, event.getItemAmount());
    }

    // ─── Fishing ──────────────────────────────────────────────────────────────

    /**
     * Handles fishing progress for {@link QuestCondition.FishCondition}.
     * Only counts successful catches ({@link PlayerFishEvent.State#CAUGHT_FISH}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        processCondition(event.getPlayer(),
                QuestCondition.FishCondition.class,
                cond -> true, 1);
    }

    // ─── Sleep ────────────────────────────────────────────────────────────────

    /**
     * Handles sleep progress for {@link QuestCondition.SleepCondition}.
     * Only counts when the player actually starts sleeping ({@code BedEnterResult.OK}).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        processCondition(event.getPlayer(),
                QuestCondition.SleepCondition.class,
                cond -> true, 1);
    }

    // ─── Villager trading ─────────────────────────────────────────────────────

    /**
     * Handles villager-trade progress for {@link QuestCondition.TradeVillagerCondition}.
     * Uses Paper's {@link PlayerPurchaseEvent} which fires on every completed trade.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerPurchase(PlayerPurchaseEvent event) {
        processCondition(event.getPlayer(),
                QuestCondition.TradeVillagerCondition.class,
                cond -> true, 1);
    }

    // ─── Generic dispatcher ───────────────────────────────────────────────────

    /**
     * Scans all active quests of the given player and increments the progress of every
     * condition that matches {@code conditionClass} and passes the {@code filter}.
     *
     * <p>When a quest is completed by this increment the method:</p>
     * <ol>
     *   <li>Saves the player data asynchronously.</li>
     *   <li>Checks for newly unlockable quests and activates them.</li>
     *   <li>Sends a completion message and plays a sound.</li>
     * </ol>
     *
     * <p>For non-completing increments an action bar progress update is shown.</p>
     *
     * @param player         the player triggering the event
     * @param conditionClass runtime class token used to filter condition types
     * @param filter         additional predicate applied to the typed condition
     * @param increment      amount to add to progress (always positive)
     * @param <T>            the specific {@link QuestCondition} subtype
     */
    /**
     * Scans all active quests of the given player and increments the progress of every
     * condition that matches {@code conditionClass} and passes the {@code filter}.
     *
     * <h3>Optimisation</h3>
     * <p>Rather than iterating every active quest and checking each condition's runtime
     * type (O(activeQuests × conditions)), this method consults the pre-built
     * <em>condition reverse-index</em> in {@link fr.zo3n.boxed.managers.QuestManager}
     * to find only the quests that actually contain a condition of the requested type.
     * If no loaded quest has such a condition the method returns after two map lookups,
     * making it effectively free on irrelevant events.</p>
     *
     * <p>When a quest is completed by this increment the method:</p>
     * <ol>
     *   <li>Schedules a debounced async data save (coalesced across rapid completions).</li>
     *   <li>Checks for newly unlockable quests and activates them.</li>
     *   <li>Sends a completion message and plays a sound.</li>
     * </ol>
     *
     * <p>For non-completing increments an action bar progress update is shown.</p>
     *
     * @param player         the player triggering the event
     * @param conditionClass runtime class token for the condition sub-type
     * @param filter         additional predicate applied to the typed condition
     * @param increment      amount to add to progress (always positive)
     * @param <T>            the specific {@link QuestCondition} subtype
     */
    private <T extends QuestCondition> void processCondition(
            Player player,
            Class<T> conditionClass,
            Predicate<T> filter,
            int increment) {

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
        if (data == null || data.getActiveQuests().isEmpty()) return;

        // Early-exit: if no quest has a condition of this type, there's nothing to do
        Map<String, List<Integer>> condIndex =
                plugin.getQuestManager().getConditionIndex(conditionClass);
        if (condIndex.isEmpty()) return;

        // Snapshot the active quest IDs — completeQuest() removes entries from the
        // set during this loop, so we need a stable copy to iterate safely.
        // toArray avoids the List wrapper overhead of List.copyOf().
        String[] activeSnapshot = data.getActiveQuests().toArray(String[]::new);

        for (String questId : activeSnapshot) {
            // Only consider quests that actually have a condition of this type
            List<Integer> condIndices = condIndex.get(questId);
            if (condIndices == null) continue;

            Quest quest = plugin.getQuestManager().getQuest(questId);
            if (quest == null) continue;

            List<QuestCondition> conditions = quest.conditions();
            for (int ci : condIndices) {
                QuestCondition condition = conditions.get(ci);
                T typed = conditionClass.cast(condition); // safe: index guarantees type

                if (!filter.test(typed)) continue;
                if (data.getConditionProgress(questId, ci) >= condition.amount()) continue;

                boolean completed = plugin.getQuestManager()
                        .incrementProgress(player, data, questId, ci, increment);

                sendFeedback(player, quest, condition, data, questId, ci, completed);

                if (completed) {
                    plugin.getPlayerDataManager().savePlayerData(data);
                    unlockNewQuests(player, data);
                    break; // quest done — move on to the next quest
                }
            }
        }
    }

    // ─── Feedback helpers ────────────────────────────────────────────────────

    /**
     * Sends progress or completion feedback to the player.
     *
     * <p>On completion: chat message + level-up sound.<br>
     * On progress: action bar update with current/target counts.</p>
     */
    private void sendFeedback(Player player, Quest quest, QuestCondition condition,
                               PlayerData data, String questId, int condIdx, boolean completed) {
        if (completed) {
            String raw = plugin.getConfig().getString(
                    "messages.quest-completed", "§a✔ Quête complétée : §e%quest%");
            player.sendMessage(LegacyComponentSerializer.legacySection()
                    .deserialize(raw.replace("%quest%", quest.name())));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        } else {
            int current = data.getConditionProgress(questId, condIdx);
            Component bar = buildProgressBar(current, condition.amount());
            Component questName = LegacyComponentSerializer.legacySection()
                    .deserialize(quest.name());
            player.sendActionBar(
                    Component.empty()
                            .append(questName)
                            .append(Component.text(" ").color(NamedTextColor.WHITE))
                            .append(bar)
                            .append(Component.text(" " + current + "/").color(NamedTextColor.GRAY))
                            .append(Component.text(String.valueOf(condition.amount())).color(NamedTextColor.WHITE))
                            .append(Component.text("  " + condition.describe()).color(NamedTextColor.DARK_GRAY))
            );
        }
    }

    /**
     * Builds a visual eight-segment progress bar as an Adventure {@link Component}.
     * Example output: {@code [████░░░░]} for 50 % progress.
     *
     * @param current current value
     * @param total   target value
     * @return the bar component
     */
    private static Component buildProgressBar(int current, int total) {
        int bars   = 8;
        int filled = total > 0 ? Math.min(current * bars / total, bars) : 0;
        return Component.text("[").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("█".repeat(filled)).color(NamedTextColor.GREEN))
                .append(Component.text("█".repeat(bars - filled)).color(NamedTextColor.DARK_GRAY))
                .append(Component.text("]").color(NamedTextColor.DARK_GRAY));
    }

    /**
     * Finds all quests whose prerequisites are now satisfied and activates them,
     * notifying the player of each newly unlocked quest.
     *
     * @param player the player whose quest tree to evaluate
     * @param data   the player's current data
     */
    private void unlockNewQuests(Player player, PlayerData data) {
        plugin.getQuestManager().getQuests().values().forEach(quest -> {
            if (plugin.getQuestManager().isQuestAvailable(data, quest)) {
                data.getActiveQuests().add(quest.id());
                data.markDirty();

                String raw = plugin.getConfig().getString(
                        "messages.quest-unlocked", "§6✦ Nouvelle quête débloquée : §e%quest%");
                player.sendMessage(LegacyComponentSerializer.legacySection()
                        .deserialize(raw.replace("%quest%", quest.name())));
            }
        });
    }
}
