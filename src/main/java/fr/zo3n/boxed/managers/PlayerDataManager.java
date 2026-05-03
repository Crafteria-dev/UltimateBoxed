package fr.zo3n.boxed.managers;

import fr.zo3n.boxed.BoxedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Manages per-player data persistence using individual YAML files.
 *
 * <p>Each player's data is stored in
 * {@code plugins/UltimateBoxed/playerdata/<uuid>.yml}.
 * Data is loaded into memory on join and saved asynchronously when modified.
 * A full synchronous save of all loaded players is performed on plugin disable.</p>
 */
public class PlayerDataManager {

    private final BoxedPlugin plugin;
    private final File dataFolder;

    /** In-memory cache: UUID → PlayerData. */
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    /**
     * Pending debounced save tasks: UUID → scheduled BukkitTask.
     * When a save request arrives and a task is already pending for that UUID,
     * the new request is silently dropped — the existing task will persist the
     * latest state of the {@link PlayerData} object when it eventually runs.
     */
    private final Map<UUID, BukkitTask> pendingSaveTasks = new HashMap<>();

    /**
     * Creates a new {@link PlayerDataManager} and ensures the data directory exists.
     *
     * @param plugin the owning plugin instance
     */
    public PlayerDataManager(BoxedPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the in-memory {@link PlayerData} for the given UUID,
     * or {@code null} if the player has not been loaded this session.
     *
     * @param playerId player UUID
     * @return the loaded data, or {@code null}
     */
    public PlayerData getPlayerData(UUID playerId) {
        return cache.get(playerId);
    }

    /**
     * Loads (or creates) the {@link PlayerData} for a joining player.
     * Caches the result in memory.
     *
     * <p>If no file exists for this UUID, default data is created and saved immediately.</p>
     *
     * @param player the joining player
     * @return the loaded or newly created {@link PlayerData}
     */
    public PlayerData loadOrCreate(Player player) {
        UUID uuid = player.getUniqueId();
        if (cache.containsKey(uuid)) {
            return cache.get(uuid);
        }

        File file = playerFile(uuid);
        PlayerData data = file.exists() ? loadFromFile(uuid, file) : createDefaultData(player);

        cache.put(uuid, data);

        if (!file.exists()) {
            savePlayerDataSync(data);
        }
        return data;
    }

    /**
     * Schedules a debounced asynchronous save of the given player's data.
     *
     * <p>If a save task is already pending for this player it is left as-is —
     * the task will capture the latest in-memory state when it fires, so there
     * is no need to reschedule.  The delay is read from
     * {@code optimization.save-delay-ticks} in {@code config.yml}
     * (default 100 ticks = 5 s).</p>
     *
     * <p>Call {@link #unload(UUID)} on player-quit to ensure the data is
     * flushed immediately rather than waiting for the deferred task.</p>
     *
     * @param data the data to persist (marked dirty automatically)
     */
    public void savePlayerData(PlayerData data) {
        data.markDirty();
        UUID uuid = data.getPlayerId();

        // A task is already scheduled for this player — it will write the latest state
        if (pendingSaveTasks.containsKey(uuid)) return;

        long delay = plugin.getConfig().getLong("optimization.save-delay-ticks", 100L);
        BukkitTask task = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            pendingSaveTasks.remove(uuid);
            if (data.isDirty()) {
                savePlayerDataSync(data);
                data.markClean();
            }
        }, delay);
        pendingSaveTasks.put(uuid, task);
    }

    /**
     * Saves the given player's data to disk synchronously.
     * This method is safe to call from an async context.
     *
     * @param data the data to persist
     */
    public void savePlayerDataSync(PlayerData data) {
        YamlConfiguration config = new YamlConfiguration();

        // Zone
        Location center = data.getZoneCenter();
        if (center != null && center.getWorld() != null) {
            config.set("zone.world",  center.getWorld().getName());
            config.set("zone.x",      center.getX());
            config.set("zone.y",      center.getY());
            config.set("zone.z",      center.getZ());
        }
        config.set("zone.chunks",           data.getZoneChunks());
        config.set("zone.tier",             data.getCurrentTier());
        config.set("world-initialized",     data.isWorldInitialized());

        // Quest sets
        config.set("completed-quests", data.getCompletedQuests().stream().sorted().toList());
        config.set("active-quests",    data.getActiveQuests().stream().sorted().toList());

        // Quest progress
        for (Map.Entry<String, int[]> entry : data.getQuestProgress().entrySet()) {
            int[] arr = entry.getValue();
            for (int i = 0; i < arr.length; i++) {
                config.set("quest-progress." + entry.getKey() + "." + i, arr[i]);
            }
        }

        try {
            config.save(playerFile(data.getPlayerId()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to save player data for " + data.getPlayerId(), e);
        }
    }

    /**
     * Saves the player's data to disk and removes them from the in-memory cache.
     * Should be called when a player disconnects.
     *
     * <p>Any pending debounced save task is cancelled first.  The data is then
     * written immediately on an async thread so that no progress is lost on quit.</p>
     *
     * @param playerId UUID of the disconnecting player
     */
    public void unload(UUID playerId) {
        PlayerData data = cache.remove(playerId);
        if (data == null) return;

        // Cancel the deferred task — we are about to flush now
        BukkitTask pending = pendingSaveTasks.remove(playerId);
        if (pending != null) {
            pending.cancel();
        }

        // Always flush on quit regardless of dirty flag (guards against edge-cases
        // where dirty was cleared by the periodic save mid-session)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            savePlayerDataSync(data);
            data.markClean();
        });
    }

    /**
     * Synchronously saves all currently cached player data to disk.
     * Called during plugin disable to ensure no data is lost.
     *
     * <p>All pending debounced tasks are cancelled before writing so that the
     * disable path is the single authoritative flusher.</p>
     */
    public void saveAll() {
        // Cancel all pending deferred saves — we flush everything now
        pendingSaveTasks.values().forEach(BukkitTask::cancel);
        pendingSaveTasks.clear();

        cache.values().forEach(data -> {
            savePlayerDataSync(data);
            data.markClean();
        });
        plugin.getLogger().info("Saved " + cache.size() + " player data file(s).");
    }

    /**
     * Asynchronously saves all {@link PlayerData} instances that have been
     * mutated since the last save ({@link PlayerData#isDirty()} is {@code true}).
     *
     * <p>Intended to be called by a periodic scheduler task as a background
     * safety net.  Already-pending debounced tasks are not affected.</p>
     */
    public void saveAllDirty() {
        int count = 0;
        for (PlayerData data : cache.values()) {
            if (data.isDirty() && !pendingSaveTasks.containsKey(data.getPlayerId())) {
                savePlayerDataSync(data);
                data.markClean();
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().fine("Periodic auto-save: flushed " + count + " dirty player data file(s).");
        }
    }

    /**
     * Resets the given player's data to default values and clears their saved file.
     *
     * @param player the player to reset
     * @return the freshly created default {@link PlayerData}
     */
    public PlayerData reset(Player player) {
        UUID uuid = player.getUniqueId();
        cache.remove(uuid);

        File file = playerFile(uuid);
        if (file.exists()) {
            file.delete();
        }

        PlayerData fresh = createDefaultData(player);
        cache.put(uuid, fresh);
        savePlayerDataSync(fresh);
        return fresh;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private File playerFile(UUID uuid) {
        return new File(dataFolder, uuid + ".yml");
    }

    /**
     * Creates default {@link PlayerData} for a brand-new player.
     * The zone centre is intentionally left {@code null} — it will be set when
     * the player runs {@code /boxed play} for the first time and their world is loaded.
     */
    private PlayerData createDefaultData(Player player) {
        return new PlayerData(player.getUniqueId(), null);
    }

    /**
     * Loads a {@link PlayerData} from a YAML file.
     *
     * @param uuid UUID of the player
     * @param file the YAML file to read
     * @return the loaded data
     */
    private PlayerData loadFromFile(UUID uuid, File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // Zone center — may be absent for players who have not yet run /boxed play
        Location center = null;
        if (config.contains("zone.world")) {
            String worldName = config.getString("zone.world");
            World world = worldName != null ? Bukkit.getWorld(worldName) : null;
            if (world != null) {
                double x = config.getDouble("zone.x", 8.0);
                double y = config.getDouble("zone.y", 64.0);
                double z = config.getDouble("zone.z", 8.0);
                center = new Location(world, x, y, z);
            }
        }

        PlayerData data = new PlayerData(uuid, center);
        data.setZoneChunks(config.getInt("zone.chunks", 1));
        data.setCurrentTier(config.getInt("zone.tier",   1));
        data.setWorldInitialized(config.getBoolean("world-initialized", false));

        // Quest sets
        data.getCompletedQuests().addAll(config.getStringList("completed-quests"));
        data.getActiveQuests().addAll(config.getStringList("active-quests"));

        // Quest progress
        ConfigurationSection progressSection = config.getConfigurationSection("quest-progress");
        if (progressSection != null) {
            for (String questId : progressSection.getKeys(false)) {
                ConfigurationSection condSection = progressSection.getConfigurationSection(questId);
                if (condSection == null) continue;

                int maxIdx = condSection.getKeys(false).stream()
                        .mapToInt(k -> {
                            try { return Integer.parseInt(k); }
                            catch (NumberFormatException e) { return -1; }
                        })
                        .max()
                        .orElse(-1);

                if (maxIdx < 0) continue;

                int[] arr = new int[maxIdx + 1];
                for (String idxStr : condSection.getKeys(false)) {
                    try {
                        arr[Integer.parseInt(idxStr)] = condSection.getInt(idxStr);
                    } catch (NumberFormatException ignored) { /* skip malformed key */ }
                }
                data.getQuestProgress().put(questId, arr);
            }
        }

        return data;
    }
}
