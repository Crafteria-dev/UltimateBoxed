package fr.zo3n.boxed.listeners;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.managers.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.UUID;


/**
 * Handles player lifecycle events for the Boxed gamemode.
 *
 * <h2>Join behaviour</h2>
 * <ul>
 *   <li>Loads (or creates) player data.</li>
 *   <li>If {@link PlayerData#isWorldInitialized()} is {@code true}: loads the
 *       player's boxed world (if not already loaded) and teleports them to it.</li>
 *   <li>Otherwise: keeps the player in the lobby and prompts them to run
 *       {@code /boxed play}.</li>
 * </ul>
 *
 * <h2>Quit behaviour</h2>
 * <ul>
 *   <li>Persists player data and releases the cached world border.</li>
 *   <li>Schedules an attempt to unload the player's boxed world if it is now empty.</li>
 * </ul>
 *
 * <h2>Respawn behaviour</h2>
 * <ul>
 *   <li>Re-applies the zone border 5 ticks after respawn (only if a zone center
 *       has been defined).</li>
 * </ul>
 */
public class PlayerJoinListener implements Listener {

    private final BoxedPlugin plugin;

    /**
     * Creates a new {@link PlayerJoinListener}.
     *
     * @param plugin the owning plugin instance
     */
    public PlayerJoinListener(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Events ───────────────────────────────────────────────────────────────

    /**
     * On join: loads player data, then either teleports the player to their boxed
     * world or instructs them to use {@code /boxed play}.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player     player = event.getPlayer();
        PlayerData data   = plugin.getPlayerDataManager().loadOrCreate(player);

        // Delay 5 ticks — ensures the client receives packets after spawn
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            if (data.isWorldInitialized()) {
                // Resume: load the player's world (possibly from disk) and teleport
                plugin.getWorldManager().getOrCreateWorld(player, world -> {
                    if (world != null && player.isOnline()) {
                        plugin.getWorldManager().teleportToWorld(player, world, data);
                    }
                });
            } else {
                // First time — remain in lobby and show clickable instructions
                player.sendMessage(buildPlayInstructions());
            }

            // Notify administrators of any pending update
            if (player.hasPermission("boxed.admin")) {
                plugin.getUpdateChecker().notifyIfOutdated(player);
            }
        }, 5L);
    }

    /**
     * On quit: saves player data, removes the cached border, then schedules
     * a world-unload attempt 1 second later (giving time for the world-change
     * event to settle before checking the player count).
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.getPlayerDataManager().unload(uuid);
        plugin.getZoneManager().removeBorder(uuid);
        plugin.getWorldManager().cleanup(uuid);

        // Unload the world after a short delay so the player has fully left
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getWorldManager().unloadWorldIfEmpty(uuid), 20L);
    }

    /**
     * On respawn: re-applies the zone border 5 ticks after the player respawns
     * (only when a zone center has already been set).
     *
     * @param event the respawn event
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            PlayerData data = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
            if (data != null && data.getZoneCenter() != null) {
                plugin.getZoneManager().applyBorder(player, data);
            }
        }, 5L);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds the first-join instruction message with a clickable {@code /boxed play}
     * button.  Hovering reveals a tooltip; clicking runs the command directly.
     *
     * @return the fully composed Adventure component
     */
    private static Component buildPlayInstructions() {
        Component button = Component.text(" [▶ Démarrer] ")
                .color(NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/boxed play"))
                .hoverEvent(HoverEvent.showText(
                        Component.text("Cliquez pour créer votre monde Boxed !")
                                .color(NamedTextColor.GREEN)));

        return Component.text("Bienvenue dans Boxed ! Cliquez sur")
                .color(NamedTextColor.GOLD)
                .append(button)
                .append(Component.text("pour démarrer votre aventure.")
                        .color(NamedTextColor.GOLD));
    }
}
