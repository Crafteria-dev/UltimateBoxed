package fr.zo3n.boxed.listeners;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.managers.WorldManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/**
 * Enforces per-player world isolation for the Boxed gamemode.
 *
 * <h2>Rules enforced</h2>
 * <ul>
 *   <li><b>Portals</b> — cancelled inside any boxed world (nether/end portals
 *       would create or link to global dimensions).</li>
 *   <li><b>Cross-world entry</b> — a player may never teleport into another
 *       player's boxed world.</li>
 *   <li><b>Unmanaged exit</b> — a player may not leave their own boxed world
 *       except through {@link WorldManager#sendToLobby(Player)}
 *       ({@code /boxed leave}) or {@link WorldManager#teleportToWorld}
 *       (managed teleports tracked by {@code teleportingPlayers}).</li>
 * </ul>
 *
 * <p>Managed teleports performed by {@link WorldManager} are whitelisted via
 * {@link WorldManager#isTeleportingToWorld(UUID)} and are never blocked.</p>
 */
public class WorldProtectionListener implements Listener {

    private final BoxedPlugin plugin;

    /**
     * Creates a new {@link WorldProtectionListener}.
     *
     * @param plugin the owning plugin instance
     */
    public WorldProtectionListener(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Portal blocking ──────────────────────────────────────────────────────

    /**
     * Cancels any portal use initiated by a player inside a boxed world.
     *
     * @param event the portal event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (plugin.getWorldManager().isBoxedWorld(event.getPlayer().getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    Component.text("Les portails sont désactivés dans les mondes Boxed.")
                            .color(NamedTextColor.RED));
        }
    }

    /**
     * Cancels any entity portal event originating from a boxed world
     * (prevents item frames, minecarts, etc. from dragging things across dimensions).
     *
     * @param event the entity portal event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (plugin.getWorldManager().isBoxedWorld(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ─── Teleport guarding ────────────────────────────────────────────────────

    /**
     * Enforces cross-world isolation on every teleport that crosses a world boundary.
     *
     * <p>Logic:</p>
     * <ol>
     *   <li>Managed teleports (flagged by {@link WorldManager}) → always allowed.</li>
     *   <li>Teleport <em>into</em> a boxed world the player does not own → cancelled.</li>
     *   <li>Teleport <em>out of</em> the player's own boxed world without the managed
     *       flag → cancelled; the player is told to use {@code /boxed leave}.</li>
     * </ol>
     *
     * @param event the teleport event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        // Managed teleports are always allowed
        if (plugin.getWorldManager().isTeleportingToWorld(uuid)) return;

        World toWorld   = event.getTo().getWorld();
        World fromWorld = event.getFrom().getWorld();

        // Block entry into a boxed world the player doesn't own
        if (toWorld != null && plugin.getWorldManager().isBoxedWorld(toWorld)) {
            UUID owner = plugin.getWorldManager().getWorldOwner(toWorld);
            if (!uuid.equals(owner)) {
                event.setCancelled(true);
                player.sendMessage(
                        Component.text("Vous ne pouvez pas entrer dans le monde d'un autre joueur.")
                                .color(NamedTextColor.RED));
                return;
            }
        }

        // Block unmanaged exit from the player's own boxed world
        if (fromWorld != null && plugin.getWorldManager().isBoxedWorld(fromWorld)) {
            UUID owner = plugin.getWorldManager().getWorldOwner(fromWorld);
            if (uuid.equals(owner)) {
                event.setCancelled(true);
                player.sendMessage(
                        Component.text("Utilisez §e/boxed leave §cpour quitter votre monde Boxed.")
                                .color(NamedTextColor.RED));
            }
        }
    }

    /**
     * Safety net: if a player ends up in a foreign boxed world despite the
     * teleport guard (e.g. via server-side commands that bypass Bukkit events),
     * they are immediately sent to the lobby.
     *
     * @param event the world-change event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        // Managed teleports are handled already
        if (plugin.getWorldManager().isTeleportingToWorld(uuid)) return;

        World current = player.getWorld();
        if (!plugin.getWorldManager().isBoxedWorld(current)) return;

        UUID owner = plugin.getWorldManager().getWorldOwner(current);
        if (!uuid.equals(owner)) {
            player.sendMessage(
                    Component.text("Accès refusé — retour au lobby.")
                            .color(NamedTextColor.RED));
            plugin.getWorldManager().sendToLobby(player);
        }
    }
}
