package fr.zo3n.boxed.gui;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.managers.PlayerData;
import fr.zo3n.boxed.quests.Quest;
import fr.zo3n.boxed.quests.QuestCondition;
import fr.zo3n.boxed.utils.BorderUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest GUI that displays the player's quests for the current tier.
 *
 * <p>Each quest occupies one slot in the inner area (slots 10–16 and 19–25 of a
 * 4-row chest). Slots are colour-coded by status:</p>
 * <ul>
 *   <li>Gray barrier — locked (prerequisites not met)</li>
 *   <li>Yellow glass pane overlay — in progress</li>
 *   <li>Green glass pane overlay — completed</li>
 * </ul>
 *
 * <p>Clicking a quest slot sends its full progress details as a chat message.
 * The GUI registers itself as a temporary {@link Listener} and self-unregisters
 * on inventory close — no blocking I/O occurs on the main thread.</p>
 */
public class QuestMenuGUI implements Listener {

    /** Total inventory size: 4 rows × 9 columns. */
    private static final int ROWS      = 4;
    private static final int GUI_SIZE  = ROWS * 9;

    /** Slot where the info compass is placed (bottom-centre). */
    private static final int INFO_SLOT = GUI_SIZE - 5;

    private final BoxedPlugin  plugin;
    private final Player       player;
    private final PlayerData   playerData;

    private Inventory inventory;

    /**
     * Creates a new {@link QuestMenuGUI}.
     *
     * @param plugin     owning plugin instance
     * @param player     the player for whom this GUI is being opened
     * @param playerData the player's current data
     */
    public QuestMenuGUI(BoxedPlugin plugin, Player player, PlayerData playerData) {
        this.plugin     = plugin;
        this.player     = player;
        this.playerData = playerData;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds the inventory, opens it for the player, and registers this object
     * as a temporary event listener.
     */
    public void open() {
        Component title = Component.text("Boxed — Quêtes  (Tier " + playerData.getCurrentTier() + ")")
                .color(NamedTextColor.GOLD);
        inventory = Bukkit.createInventory(null, GUI_SIZE, title);

        populate();
        player.openInventory(inventory);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ─── Inventory event handlers ─────────────────────────────────────────────

    /**
     * Cancels all clicks inside this GUI and sends quest details when a quest slot
     * is clicked.
     *
     * @param event the click event
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player clicker)) return;
        if (!clicker.equals(player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GUI_SIZE) return;

        // Map slot back to quest index
        List<Quest> tierQuests = plugin.getQuestManager().getQuestsForTier(playerData.getCurrentTier());
        int questIndex = slotToQuestIndex(slot);
        if (questIndex >= 0 && questIndex < tierQuests.size()) {
            sendQuestDetail(tierQuests.get(questIndex));
        }
    }

    /**
     * Unregisters this listener when the GUI is closed to prevent memory leaks.
     *
     * @param event the close event
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        HandlerList.unregisterAll(this);
    }

    // ─── GUI building ─────────────────────────────────────────────────────────

    /**
     * Fills the inventory: border glass panes, quest icons, and the info compass.
     */
    private void populate() {
        // Border fill — top row, bottom row, left/right columns
        ItemStack border = buildItem(Material.GRAY_STAINED_GLASS_PANE,
                Component.text(" "), List.of());
        for (int i = 0; i < GUI_SIZE; i++) {
            inventory.setItem(i, border);
        }

        // Quest icons in inner area (slots 10–16, 19–25)
        List<Quest> tierQuests = plugin.getQuestManager().getQuestsForTier(playerData.getCurrentTier());
        int slot = 10;
        for (Quest quest : tierQuests) {
            if (slot >= GUI_SIZE - 9) break; // never write into bottom border row
            inventory.setItem(slot, buildQuestIcon(quest));
            slot++;
            // Skip right border (col 8) and left border (col 0) of next row
            if (slot % 9 == 8) slot += 2;
        }

        // Info panel
        inventory.setItem(INFO_SLOT, buildInfoItem());
    }

    /**
     * Returns the quest list index for a given inventory slot, or {@code -1} if the
     * slot is not a quest slot.
     *
     * @param slot raw inventory slot
     * @return quest index, or -1
     */
    private int slotToQuestIndex(int slot) {
        // Content slots: 10–16 and 19–25 (7 per row)
        if (slot >= 10 && slot <= 16) return slot - 10;
        if (slot >= 19 && slot <= 25) return (slot - 19) + 7;
        return -1;
    }

    // ─── Item builders ────────────────────────────────────────────────────────

    /**
     * Builds the icon item for the given quest, colour-coded by its current status.
     *
     * @param quest the quest to represent
     * @return the icon {@link ItemStack}
     */
    private ItemStack buildQuestIcon(Quest quest) {
        QuestStatus status = getStatus(quest);
        Material displayMat = switch (status) {
            case LOCKED      -> Material.BARRIER;
            case IN_PROGRESS -> quest.icon();
            case COMPLETED   -> quest.icon();
        };

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(quest.description()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Statut : ").color(NamedTextColor.WHITE)
                .append(Component.text(status.label()).color(status.color()))
                .decoration(TextDecoration.ITALIC, false));

        if (status == QuestStatus.IN_PROGRESS) {
            lore.add(Component.empty());
            lore.add(Component.text("Conditions :").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            List<QuestCondition> conditions = quest.conditions();
            for (int i = 0; i < conditions.size(); i++) {
                QuestCondition cond = conditions.get(i);
                int current = playerData.getConditionProgress(quest.id(), i);
                boolean done = current >= cond.amount();
                lore.add(Component.text("  " + (done ? "✔ " : "◆ ") + cond.describe()
                        + " (" + current + "/" + cond.amount() + ")")
                        .color(done ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Cliquez pour voir les détails").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        // Parse the quest name — it may contain §-colour codes from quests.yml
        Component name = LegacyComponentSerializer.legacySection()
                .deserialize(quest.name())
                .decoration(TextDecoration.ITALIC, false);

        return buildItem(displayMat, name, lore);
    }

    /**
     * Builds the info compass shown in the bottom-centre slot.
     *
     * @return the info {@link ItemStack}
     */
    private ItemStack buildInfoItem() {
        int blockSize = (int) BorderUtils.chunksToBlockSize(playerData.getZoneChunks());
        List<Component> lore = List.of(
                Component.text("Palier actuel : " + playerData.getCurrentTier())
                        .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Zone : " + playerData.getZoneChunks() + " chunk(s)"
                        + " (" + blockSize + "×" + blockSize + " blocs)")
                        .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("Quêtes complétées : " + playerData.getCompletedQuests().size())
                        .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
        );
        return buildItem(Material.COMPASS,
                Component.text("Informations").color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false),
                lore);
    }

    /**
     * Creates an {@link ItemStack} with a display name and lore, suppressing italic.
     *
     * @param material item type
     * @param name     display name component
     * @param lore     lore lines
     * @return the constructed item
     */
    private static ItemStack buildItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        // Use a pre-sized ArrayList and a plain loop — avoids the stream + lambda
        // allocation that would occur on every GUI item render.
        List<Component> processed = new ArrayList<>(lore.size());
        for (Component c : lore) {
            processed.add(c.decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(processed);
        item.setItemMeta(meta);
        return item;
    }

    // ─── Quest detail (chat) ──────────────────────────────────────────────────

    /**
     * Sends a chat message with the full condition and reward details of a quest.
     *
     * @param quest the quest to describe
     */
    private void sendQuestDetail(Quest quest) {
        QuestStatus status = getStatus(quest);
        player.sendMessage(LegacyComponentSerializer.legacySection()
                .deserialize("§6══ " + quest.name() + " §6══"));
        player.sendMessage(Component.text("Statut : ").color(NamedTextColor.WHITE)
                .append(Component.text(status.label()).color(status.color())));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Conditions :").color(NamedTextColor.YELLOW));

        List<QuestCondition> conditions = quest.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            QuestCondition cond = conditions.get(i);
            int current = playerData.getConditionProgress(quest.id(), i);
            boolean done = current >= cond.amount();
            String condLine = "  " + (done ? "§a✔ " : "§e◆ ")
                    + cond.describe() + " §7(" + current + "/" + cond.amount() + ")";
            player.sendMessage(
                    LegacyComponentSerializer.legacySection().deserialize(condLine));
        }

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Récompenses :").color(NamedTextColor.GOLD));
        quest.rewards().forEach(r ->
                player.sendMessage(Component.text("  + " + r.describe()).color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
    }

    // ─── Status helper ────────────────────────────────────────────────────────

    /**
     * Determines the current display status of a quest for this player.
     *
     * @param quest the quest to evaluate
     * @return the corresponding {@link QuestStatus}
     */
    private QuestStatus getStatus(Quest quest) {
        if (playerData.getCompletedQuests().contains(quest.id())) return QuestStatus.COMPLETED;
        if (playerData.getActiveQuests().contains(quest.id()))    return QuestStatus.IN_PROGRESS;
        return QuestStatus.LOCKED;
    }

    // ─── Status enum ──────────────────────────────────────────────────────────

    /**
     * Represents the possible display statuses of a quest in the GUI.
     */
    private enum QuestStatus {
        LOCKED("Verrouillé",   NamedTextColor.GRAY),
        IN_PROGRESS("En cours",NamedTextColor.YELLOW),
        COMPLETED("Complété",  NamedTextColor.GREEN);

        private final String         label;
        private final NamedTextColor color;

        QuestStatus(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }

        /** @return display label for this status */
        public String label() { return label; }

        /** @return Adventure colour for this status */
        public NamedTextColor color() { return color; }
    }
}
