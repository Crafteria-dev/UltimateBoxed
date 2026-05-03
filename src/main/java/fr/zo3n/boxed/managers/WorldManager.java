package fr.zo3n.boxed.managers;

import fr.zo3n.boxed.BoxedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Manages per-player Minecraft worlds for the Boxed gamemode.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>An administrator places a pre-built world folder into
 *       {@code plugins/UltimateBoxed/template/}.</li>
 *   <li>When a player runs {@code /boxed play} for the first time,
 *       the template is copied asynchronously to the server's world
 *       container under the name {@code boxed_<uuid-without-dashes>}.</li>
 *   <li>On subsequent sessions the world is loaded lazily (on join or on
 *       {@code /boxed play}) and unloaded when the owner disconnects.</li>
 * </ol>
 *
 * <h2>Isolation guarantees</h2>
 * <ul>
 *   <li>Each player world is entirely separate from the main world.</li>
 *   <li>No player can enter another player's world (enforced by
 *       {@link fr.zo3n.boxed.listeners.WorldProtectionListener}).</li>
 *   <li>Portals are blocked inside boxed worlds.</li>
 * </ul>
 */
public class WorldManager {

    /** Directory-name prefix used for every boxed world. */
    public static final String WORLD_PREFIX = "boxed_";

    private final BoxedPlugin plugin;

    /**
     * UUIDs currently being teleported <em>by this manager</em>.
     * Used by {@link fr.zo3n.boxed.listeners.WorldProtectionListener} to skip
     * protection checks for managed teleports.
     */
    private final Set<UUID> teleportingPlayers  = new HashSet<>();

    /** UUIDs whose world is currently being copied from the template (async). */
    private final Set<UUID> pendingCreation = new HashSet<>();

    /**
     * Creates a new {@link WorldManager}.
     *
     * @param plugin the owning plugin instance
     */
    public WorldManager(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── Naming helpers ───────────────────────────────────────────────────────

    /**
     * Returns the world (directory) name for the given player UUID.
     *
     * <p>Example: {@code boxed_550e8400e29b41d4a716446655440000}</p>
     *
     * @param playerId player UUID
     * @return world name (38 characters)
     */
    public String getWorldName(UUID playerId) {
        return WORLD_PREFIX + playerId.toString().replace("-", "");
    }

    /**
     * Returns {@code true} if the given world is a managed boxed world.
     *
     * @param world world to test (may be {@code null})
     * @return {@code true} when the world's name starts with {@value #WORLD_PREFIX}
     */
    public boolean isBoxedWorld(World world) {
        return world != null && world.getName().startsWith(WORLD_PREFIX);
    }

    /**
     * Derives the owner UUID from a boxed world's name.
     *
     * @param world a boxed world
     * @return the owner UUID, or {@code null} if the name is malformed
     */
    public UUID getWorldOwner(World world) {
        if (!isBoxedWorld(world)) return null;
        String hex = world.getName().substring(WORLD_PREFIX.length());
        if (hex.length() != 32) return null;
        try {
            return UUID.fromString(
                    hex.substring(0, 8) + "-" + hex.substring(8, 12)  + "-" +
                    hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" +
                    hex.substring(20));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the currently loaded {@link World} for the given player,
     * or {@code null} if it is not loaded.
     *
     * @param playerId player UUID
     * @return the loaded World, or {@code null}
     */
    public World getPlayerWorld(UUID playerId) {
        return Bukkit.getWorld(getWorldName(playerId));
    }

    /**
     * Returns {@code true} if a world directory already exists for the player
     * (regardless of whether it is currently loaded).
     *
     * @param playerId player UUID
     * @return {@code true} if the folder and its {@code level.dat} exist
     */
    public boolean hasWorldDirectory(UUID playerId) {
        File dir = new File(Bukkit.getWorldContainer(), getWorldName(playerId));
        return dir.isDirectory() && new File(dir, "level.dat").exists();
    }

    // ─── World lifecycle ──────────────────────────────────────────────────────

    /**
     * Ensures the boxed world for a player exists and is loaded, then invokes
     * {@code callback} on the main thread with the ready {@link World}.
     *
     * <p>If the world folder does not yet exist, the template is copied
     * asynchronously (files) then the world is loaded synchronously (Bukkit
     * requires main-thread world loading).  The callback receives {@code null}
     * on any failure.</p>
     *
     * @param player   the requesting player
     * @param callback called on the main thread once the world is ready (or null)
     */
    public void getOrCreateWorld(Player player, Consumer<World> callback) {
        UUID uuid = player.getUniqueId();

        // Already loaded?
        World loaded = getPlayerWorld(uuid);
        if (loaded != null) {
            callback.accept(loaded);
            return;
        }

        // Guard against duplicate concurrent requests
        if (pendingCreation.contains(uuid)) {
            player.sendMessage(Component.text("Génération déjà en cours, patientez…")
                    .color(NamedTextColor.YELLOW));
            return;
        }

        if (hasWorldDirectory(uuid)) {
            // Folder exists but world is not loaded — load and configure
            World existing = loadWorldSync(getWorldName(uuid));
            if (existing != null) applyWorldOptimizations(existing);
            callback.accept(existing);
            return;
        }

        // ── Template copy path ────────────────────────────────────────────────
        File template = new File(plugin.getDataFolder(), "template");
        if (!new File(template, "level.dat").exists()) {
            player.sendMessage(Component.text(
                    "Le dossier template est absent. Contactez un administrateur.")
                    .color(NamedTextColor.RED));
            plugin.getLogger().severe("Template world missing at: " + template.getAbsolutePath());
            callback.accept(null);
            return;
        }

        pendingCreation.add(uuid);

        File worldDir = new File(Bukkit.getWorldContainer(), getWorldName(uuid));

        // Animated action bar — self-cancels once the UUID leaves pendingCreation
        new BukkitRunnable() {
            int frame = 0;
            @Override public void run() {
                if (!player.isOnline() || !pendingCreation.contains(uuid)) {
                    cancel();
                    return;
                }
                player.sendActionBar(buildGeneratingFrame(frame++));
            }
        }.runTaskTimer(plugin, 0L, 8L);

        // Async file copy → sync world load
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyDirectory(template.toPath(), worldDir.toPath());
                // Remove lock files so Minecraft accepts the copied world
                deleteIfExists(new File(worldDir, "session.lock"));
                deleteIfExists(new File(worldDir, "uid.dat"));
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to copy template world for " + player.getName(), e);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pendingCreation.remove(uuid); // spinner self-cancels on next tick
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                "Erreur lors de la création de votre monde. Réessayez.")
                                .color(NamedTextColor.RED));
                    }
                    callback.accept(null);
                });
                return;
            }

            // Load on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pendingCreation.remove(uuid); // spinner self-cancels on next tick
                if (!player.isOnline()) { callback.accept(null); return; }

                World world = loadWorldSync(getWorldName(uuid));
                if (world == null) {
                    player.sendMessage(Component.text(
                            "Erreur lors du chargement de votre monde. Réessayez.")
                            .color(NamedTextColor.RED));
                } else {
                    applyWorldOptimizations(world);
                }
                callback.accept(world);
            });
        });
    }

    /**
     * Teleports a player to their boxed world at their zone centre (or the world
     * spawn if the centre is not yet defined) and applies their world border.
     *
     * <p>The teleport bypasses {@link fr.zo3n.boxed.listeners.WorldProtectionListener}
     * via an internal flag that is cleared 2 ticks after the call.</p>
     *
     * @param player     the player to teleport
     * @param world      their loaded boxed world (must not be {@code null})
     * @param playerData the player's persistent data
     */
    public void teleportToWorld(Player player, World world, PlayerData playerData) {
        double cx, cy, cz;
        Location stored = playerData.getZoneCenter();

        if (stored != null) {
            cx = stored.getX();
            cy = stored.getY();
            cz = stored.getZ();
        } else {
            Location spawn = world.getSpawnLocation();
            cx = spawn.getX();
            cy = spawn.getY();
            cz = spawn.getZ();
        }

        // Find the highest safe block at the zone centre
        Location safe = world.getHighestBlockAt((int) cx, (int) cz)
                .getLocation().add(0.5, 1.0, 0.5);
        safe.setYaw(0);
        safe.setPitch(0);

        teleportingPlayers.add(player.getUniqueId());
        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
        // Clear the bypass flag after the PlayerChangedWorldEvent has fired
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> teleportingPlayers.remove(player.getUniqueId()), 3L);

        plugin.getZoneManager().applyBorder(player, playerData);
    }

    /**
     * Returns {@code true} while a player is being teleported to their world
     * by this manager.
     * Used by {@link fr.zo3n.boxed.listeners.WorldProtectionListener} to skip
     * protection for managed teleports.
     *
     * @param playerId player UUID
     * @return {@code true} during the managed teleport window
     */
    public boolean isTeleportingToWorld(UUID playerId) {
        return teleportingPlayers.contains(playerId);
    }

    /**
     * Teleports a player to the configured lobby world (non-boxed).
     * Falls back to the first non-boxed world available.
     * Removes the player's world border after the teleport.
     *
     * @param player the player to send to the lobby
     */
    public void sendToLobby(Player player) {
        String lobbyName = plugin.getConfig().getString("world.lobby-world", "world");
        World lobby = Bukkit.getWorld(lobbyName);

        if (lobby == null || isBoxedWorld(lobby)) {
            lobby = Bukkit.getWorlds().stream()
                    .filter(w -> !isBoxedWorld(w))
                    .findFirst()
                    .orElse(Bukkit.getWorlds().get(0));
        }

        teleportingPlayers.add(player.getUniqueId());
        player.teleport(lobby.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> teleportingPlayers.remove(player.getUniqueId()), 3L);

        plugin.getZoneManager().removeBorder(player.getUniqueId());
    }

    /**
     * Saves and unloads the player's boxed world if it is loaded and empty.
     * Does nothing if {@code world.unload-when-empty} is disabled in config.
     *
     * @param playerId UUID of the world owner
     */
    public void unloadWorldIfEmpty(UUID playerId) {
        if (!plugin.getConfig().getBoolean("world.unload-when-empty", true)) return;
        World world = getPlayerWorld(playerId);
        if (world == null || !world.getPlayers().isEmpty()) return;
        if (Bukkit.unloadWorld(world, true)) {
            plugin.getLogger().fine("Unloaded boxed world: " + world.getName());
        }
    }

    /**
     * Saves and unloads all currently loaded boxed worlds.
     * Called from {@link BoxedPlugin#onDisable()}.
     */
    public void unloadAllWorlds() {
        Bukkit.getWorlds().stream()
                .filter(this::isBoxedWorld)
                .forEach(w -> Bukkit.unloadWorld(w, true));
    }

    /**
     * Removes any lingering state for the given player from internal tracking sets.
     * Safe to call even if the player was not tracked.
     *
     * <p>Normally the sets self-clean via scheduled tasks, but calling this on quit
     * prevents edge-case memory leaks when a player disconnects during a world
     * creation or teleport operation.</p>
     *
     * @param playerId UUID of the departing player
     */
    public void cleanup(UUID playerId) {
        teleportingPlayers.remove(playerId);
        pendingCreation.remove(playerId);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    /**
     * Builds one frame of the world-generation action bar animation.
     * Cycles through four fill states: {@code [█░░░]} → {@code [██░░]} →
     * {@code [███░]} → {@code [████]}, then repeats.
     *
     * @param tick monotonically increasing counter (wraps automatically)
     * @return the action bar {@link Component} for this frame
     */
    private static Component buildGeneratingFrame(int tick) {
        int filled = (tick % 4) + 1;
        return Component.text("Génération du monde ").color(NamedTextColor.YELLOW)
                .append(Component.text("[").color(NamedTextColor.DARK_GRAY))
                .append(Component.text("█".repeat(filled)).color(NamedTextColor.GREEN))
                .append(Component.text("█".repeat(4 - filled)).color(NamedTextColor.DARK_GRAY))
                .append(Component.text("]").color(NamedTextColor.DARK_GRAY));
    }

    /**
     * Applies server-side optimisation game rules to a boxed world.
     * Called every time a player world is loaded (idempotent since game rules
     * are stored in {@code level.dat} and persist across sessions).
     *
     * <ul>
     *   <li>{@code DO_PATROL_SPAWNING=false} — no pillager patrols near villages
     *       (irrelevant in isolated fresh worlds, saves mob-AI overhead).</li>
     *   <li>{@code DO_TRADER_SPAWNING=false} — no wandering trader spawning
     *       (would waste mob slots in small zones).</li>
     *   <li>{@code DISABLE_RAIDS=true} — raids cannot start without a Bad Omen
     *       villager nearby, but disabling explicitly prevents any edge-case
     *       processing cost.</li>
     * </ul>
     *
     * @param world the world to configure
     */
    private void applyWorldOptimizations(World world) {
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DISABLE_RAIDS,      true);
    }

    /**
     * Loads a world by name on the main thread.
     * {@link WorldCreator} auto-detects the world type from {@code level.dat}.
     *
     * @param worldName the folder/world name
     * @return the loaded World, or {@code null} on failure
     */
    private World loadWorldSync(String worldName) {
        return new WorldCreator(worldName).createWorld();
    }

    /**
     * Recursively copies {@code src} into {@code dst}, skipping player-specific
     * sub-directories ({@code playerdata/}, {@code stats/}, {@code advancements/})
     * so that each player starts fresh in their copy.
     *
     * @param src source world directory
     * @param dst destination directory (created if absent)
     * @throws IOException on any I/O error
     */
    private static void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path entry : (Iterable<Path>) walk::iterator) {
                String relative = src.relativize(entry).toString();
                // Skip player-bound data directories
                if (relative.startsWith("playerdata") ||
                    relative.startsWith("stats")      ||
                    relative.startsWith("advancements")) {
                    continue;
                }
                Path target = dst.resolve(relative);
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteIfExists(File file) {
        if (file.exists()) file.delete();
    }
}
