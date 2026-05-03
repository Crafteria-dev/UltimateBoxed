package fr.zo3n.boxed.managers;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.utils.BorderUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player {@link WorldBorder} instances for the Boxed gamemode.
 *
 * <p>Uses Paper's {@code player.setWorldBorder(WorldBorder)} API (not the global
 * world border) so each player has their own independent zone boundary.</p>
 *
 * <p>WorldBorder instances are created via {@code Bukkit.createWorldBorder()} and
 * cached in memory for the duration of the player's session.
 * Calling {@link #removeBorder(UUID)} resets the player to the world's default border.</p>
 */
public class ZoneManager {

    private final BoxedPlugin plugin;

    /** Cache of per-player WorldBorder objects. */
    private final Map<UUID, WorldBorder> playerBorders = new HashMap<>();

    /**
     * Creates a new {@link ZoneManager}.
     *
     * @param plugin the owning plugin instance
     */
    public ZoneManager(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Immediately applies (or refreshes) the world border for the given player
     * based on their current {@link PlayerData}.
     *
     * <p>Safe to call multiple times; always reflects the latest zone data.</p>
     *
     * @param player     online player to update
     * @param playerData the player's zone data
     */
    public void applyBorder(Player player, PlayerData playerData) {
        if (playerData.getZoneCenter() == null) return; // world not yet initialised

        WorldBorder border = getOrCreateBorder(player.getUniqueId());

        double size = BorderUtils.chunksToBlockSize(playerData.getZoneChunks());
        configureBorder(border, playerData.getZoneCenter());

        border.setSize(size);
        player.setWorldBorder(border);

        plugin.getLogger().fine("Border applied to " + player.getName()
                + " — size=" + size + ", center=("
                + playerData.getZoneCenter().getX() + ","
                + playerData.getZoneCenter().getZ() + ")");
    }

    /**
     * Expands the player's zone by {@code chunksToAdd} chunks, animates the border
     * transition, and notifies the player with a title and sound.
     *
     * <p>This method also persists the updated {@link PlayerData} to disk.</p>
     *
     * @param player      the online player whose zone is expanding
     * @param playerData  the player's data (will be mutated: zoneChunks incremented)
     * @param chunksToAdd number of chunks to add to the zone radius
     */
    public void expandZone(Player player, PlayerData playerData, int chunksToAdd) {
        if (playerData.getZoneCenter() == null) return; // world not yet initialised

        int oldChunks = playerData.getZoneChunks();
        int newChunks = oldChunks + chunksToAdd;
        playerData.setZoneChunks(newChunks);

        double oldSize = BorderUtils.chunksToBlockSize(oldChunks);
        double newSize = BorderUtils.chunksToBlockSize(newChunks);
        int animSecs   = plugin.getConfig().getInt("zone.expand-animation-duration", 3);

        WorldBorder border = getOrCreateBorder(player.getUniqueId());
        configureBorder(border, playerData.getZoneCenter());
        border.setSize(oldSize);          // snap to old size first
        border.setSize(newSize, animSecs); // then animate to new size

        player.setWorldBorder(border);

        // Persist
        plugin.getPlayerDataManager().savePlayerData(playerData);

        // Notify player
        String titleRaw = plugin.getConfig().getString(
                "messages.zone-expanded", "§aZone agrandie ! +%chunks% chunk(s)");
        String subtitleRaw = plugin.getConfig().getString(
                "messages.zone-expanded-subtitle", "§7%size%×%size% blocs disponibles");

        String titleText    = titleRaw.replace("%chunks%", String.valueOf(chunksToAdd));
        String subtitleText = subtitleRaw
                .replace("%size%", String.valueOf((int) newSize));

        player.showTitle(Title.title(
                fromLegacy(titleText),
                fromLegacy(subtitleText),
                Title.Times.times(
                        Duration.ofMillis(300),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(700)
                )
        ));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);

        plugin.getLogger().info(player.getName() + " zone expanded to "
                + newChunks + " chunk(s) (" + (int) newSize + "×" + (int) newSize + " blocks)");
    }

    /**
     * Removes the custom world border from a player, reverting them to the world's
     * default border. Also purges the cached border from memory.
     *
     * @param playerId UUID of the player to reset
     */
    public void removeBorder(UUID playerId) {
        playerBorders.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.setWorldBorder(null);
        }
    }

    /**
     * Re-applies the correct border to every currently online player.
     * Useful after a {@code /boxed reload}.
     *
     * @param dataManager the data manager used to fetch each player's data
     */
    public void reapplyAll(PlayerDataManager dataManager) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = dataManager.getPlayerData(player.getUniqueId());
            if (data != null) {
                applyBorder(player, data);
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Returns the cached WorldBorder for the given player, creating a new one if absent.
     *
     * @param playerId player UUID
     * @return the WorldBorder (never null)
     */
    private WorldBorder getOrCreateBorder(UUID playerId) {
        return playerBorders.computeIfAbsent(playerId, k -> Bukkit.createWorldBorder());
    }

    /**
     * Applies common border settings (centre, damage, warning) to a WorldBorder.
     *
     * @param border border to configure
     * @param center zone centre location
     * @param size   total side length in blocks
     */
    private void configureBorder(WorldBorder border, Location center) {
        border.setCenter(center.getX(), center.getZ());
        border.setDamageAmount(plugin.getConfig().getDouble("zone.damage-amount", 0.2));
        border.setDamageBuffer(plugin.getConfig().getDouble("zone.damage-buffer", 0.5));
        border.setWarningDistance(plugin.getConfig().getInt("zone.warning-distance", 5));
        border.setWarningTime(5);
    }

    /**
     * Converts a legacy §-colour-code string to an Adventure {@link Component}.
     *
     * @param text string with optional §-codes
     * @return Adventure component
     */
    private static Component fromLegacy(String text) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection()
                .deserialize(text);
    }
}
