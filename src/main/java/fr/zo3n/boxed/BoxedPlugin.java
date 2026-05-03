package fr.zo3n.boxed;

import fr.zo3n.boxed.commands.BoxedCommand;
import fr.zo3n.boxed.listeners.PlayerJoinListener;
import fr.zo3n.boxed.listeners.QuestProgressListener;
import fr.zo3n.boxed.listeners.WorldProtectionListener;
import fr.zo3n.boxed.managers.PlayerDataManager;
import fr.zo3n.boxed.managers.QuestManager;
import fr.zo3n.boxed.managers.WorldManager;
import fr.zo3n.boxed.managers.ZoneManager;
import fr.zo3n.boxed.utils.UpdateChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Entry point of the BoxedPlugin.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>{@link #onEnable()} — copies default config/resources, instantiates managers,
 *       loads quests, registers listeners and the main command.</li>
 *   <li>{@link #onDisable()} — synchronously flushes all in-memory player data to disk.</li>
 * </ol>
 *
 * <p>All managers are available through typed getters so that other components can
 * access shared state without field injection or service locators.</p>
 */
public final class BoxedPlugin extends JavaPlugin {

    private ZoneManager        zoneManager;
    private QuestManager       questManager;
    private PlayerDataManager  playerDataManager;
    private WorldManager       worldManager;
    private UpdateChecker      updateChecker;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        // Copy default config/resource files to the plugin data folder
        saveDefaultConfig();
        saveResource("quests.yml", false);

        // Ensure the template folder exists and warn the operator if it is incomplete
        File templateDir = new File(getDataFolder(), "template");
        if (!templateDir.exists()) templateDir.mkdirs();
        if (!new File(templateDir, "level.dat").exists()) {
            getLogger().warning("=== BOXED — Configuration requise ===");
            getLogger().warning("La template de monde est absente ou incomplète.");
            getLogger().warning("Chemin attendu : " + templateDir.getAbsolutePath());
            getLogger().warning("Copiez une map Minecraft dans ce dossier (doit contenir level.dat).");
            getLogger().warning("/boxed play sera bloqué jusqu'à ce que la template soit en place.");
            getLogger().warning("=====================================");
        }

        // Instantiate managers (order matters: ZoneManager references PlayerDataManager
        // and vice-versa through the plugin instance, but neither calls the other
        // during construction — only at runtime via event handlers)
        zoneManager       = new ZoneManager(this);
        questManager      = new QuestManager(this);
        playerDataManager = new PlayerDataManager(this);
        worldManager      = new WorldManager(this);
        updateChecker     = new UpdateChecker(this);

        // Load quest definitions from quests.yml
        questManager.loadQuests();

        // Periodic background save — flushes all dirty player data every N ticks
        // as a safety net between the debounced per-event saves.
        long autoSaveInterval = getConfig().getLong("optimization.auto-save-interval-ticks", 6000L);
        getServer().getScheduler().runTaskTimerAsynchronously(
                this,
                () -> playerDataManager.saveAllDirty(),
                autoSaveInterval,
                autoSaveInterval
        );

        // Register event listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerJoinListener(this),       this);
        pm.registerEvents(new QuestProgressListener(this),    this);
        pm.registerEvents(new WorldProtectionListener(this),  this);

        // Register /boxed command
        PluginCommand cmd = getCommand("boxed");
        if (cmd != null) {
            var handler = new BoxedCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().severe("Command 'boxed' is not declared in plugin.yml — commands will not work.");
        }

        // Vérification des mises à jour (async — ne bloque pas le démarrage)
        updateChecker.checkAsync();

        getLogger().info("UltimateBoxed v" + getPluginMeta().getVersion() + " activé avec succès — by ZO3N");
    }

    @Override
    public void onDisable() {
        // Unload all boxed worlds before data save so chunks are properly flushed
        if (worldManager != null) {
            worldManager.unloadAllWorlds();
        }
        // Synchronous full save — ensures no data is lost during server stop
        if (playerDataManager != null) {
            playerDataManager.saveAll();
        }
        getLogger().info("UltimateBoxed désactivé — données sauvegardées.");
    }

    // ─── Manager accessors ────────────────────────────────────────────────────

    /**
     * Returns the {@link ZoneManager} responsible for per-player world borders.
     *
     * @return the zone manager (never null after {@link #onEnable()})
     */
    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    /**
     * Returns the {@link QuestManager} responsible for quest definitions and progression.
     *
     * @return the quest manager (never null after {@link #onEnable()})
     */
    public QuestManager getQuestManager() {
        return questManager;
    }

    /**
     * Returns the {@link PlayerDataManager} responsible for persistence.
     *
     * @return the player data manager (never null after {@link #onEnable()})
     */
    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    /**
     * Returns the {@link WorldManager} responsible for per-player world lifecycle.
     *
     * @return the world manager (never null after {@link #onEnable()})
     */
    public WorldManager getWorldManager() {
        return worldManager;
    }

    /**
     * Returns the {@link UpdateChecker} that monitors GitHub releases.
     *
     * @return the update checker (never null after {@link #onEnable()})
     */
    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}
