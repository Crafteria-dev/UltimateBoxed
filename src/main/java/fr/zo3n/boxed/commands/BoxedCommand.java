package fr.zo3n.boxed.commands;

import fr.zo3n.boxed.BoxedPlugin;
import fr.zo3n.boxed.gui.QuestMenuGUI;
import fr.zo3n.boxed.managers.PlayerData;
import fr.zo3n.boxed.utils.BorderUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;

import java.util.List;

/**
 * Handles the {@code /boxed} command and all its sub-commands.
 *
 * <p>Sub-commands:</p>
 * <ul>
 *   <li>{@code quests} — opens the quest GUI (requires {@code boxed.use})</li>
 *   <li>{@code info}   — displays current zone and progression info (requires {@code boxed.use})</li>
 *   <li>{@code setzone <player> <size>} — forces a player's zone size (requires {@code boxed.admin})</li>
 *   <li>{@code reset <player>}  — resets a player's progression (requires {@code boxed.admin})</li>
 *   <li>{@code reload} — reloads config.yml and quests.yml (requires {@code boxed.admin})</li>
 * </ul>
 */
public class BoxedCommand implements CommandExecutor, TabCompleter {

    private final BoxedPlugin plugin;

    /**
     * Creates a new {@link BoxedCommand}.
     *
     * @param plugin the owning plugin instance
     */
    public BoxedCommand(BoxedPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── CommandExecutor ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "play"    -> handlePlay(sender);
            case "leave"   -> handleLeave(sender);
            case "quests"  -> handleQuests(sender);
            case "info"    -> handleInfo(sender);
            case "setzone" -> handleSetZone(sender, args);
            case "reset"   -> handleReset(sender, args);
            case "reload"  -> handleReload(sender);
            case "status"  -> handleStatus(sender);
            default -> {
                sender.sendMessage(Component.text("Sous-commande inconnue. Tapez /boxed pour l'aide.")
                        .color(NamedTextColor.RED));
                yield true;
            }
        };
    }

    // ─── TabCompleter ─────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            var subs = sender.hasPermission("boxed.admin")
                    ? List.of("play", "leave", "quests", "info", "setzone", "reset", "reload", "status")
                    : List.of("play", "leave", "quests", "info");
            return subs.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("reset")
                || args[0].equalsIgnoreCase("setzone"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }

    // ─── Sub-command handlers ─────────────────────────────────────────────────

    /**
     * Enters (or creates) the player's personal boxed world.
     *
     * <p>First call:</p>
     * <ol>
     *   <li>Copies the template world asynchronously.</li>
     *   <li>Sets the zone centre from the world spawn.</li>
     *   <li>Unlocks all tier-1 quests.</li>
     *   <li>Sends the player their first-time welcome message.</li>
     * </ol>
     *
     * <p>Subsequent calls: loads the world (if unloaded) and teleports the player
     * to the top of their stored zone centre.</p>
     *
     * @param sender command sender (must be a player)
     * @return {@code true} always
     */
    private boolean handlePlay(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande ne peut être utilisée qu'en jeu.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("boxed.use")) {
            player.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(Component.text("Erreur : données non chargées.")
                    .color(NamedTextColor.RED));
            return true;
        }

        // Already in their own world?
        World current = player.getWorld();
        if (plugin.getWorldManager().isBoxedWorld(current) &&
                player.getUniqueId().equals(plugin.getWorldManager().getWorldOwner(current))) {
            player.sendMessage(Component.text("Vous êtes déjà dans votre monde Boxed.")
                    .color(NamedTextColor.YELLOW));
            return true;
        }

        plugin.getWorldManager().getOrCreateWorld(player, world -> {
            if (world == null || !player.isOnline()) return;

            boolean firstTime = !data.isWorldInitialized();

            if (firstTime) {
                // Derive zone centre from world spawn, snapped to chunk centre
                Location spawn  = world.getSpawnLocation();
                double[] center = BorderUtils.snapToChunkCenter(spawn.getX(), spawn.getZ());
                int safeY = world.getHighestBlockAt((int) center[0], (int) center[1]).getY() + 1;
                Location zoneCenter = new Location(world, center[0], safeY, center[1]);
                data.setZoneCenter(zoneCenter);
                data.setWorldInitialized(true);

                // Unlock tier-1 quests with no prerequisites
                plugin.getQuestManager().getQuestsForTier(1).forEach(quest -> {
                    if (quest.prerequisites().isEmpty()) {
                        data.getActiveQuests().add(quest.id());
                    }
                });
                data.markDirty();
                plugin.getPlayerDataManager().savePlayerData(data);
            }

            plugin.getWorldManager().teleportToWorld(player, world, data);

            String msgKey = firstTime ? "messages.play-first-time" : "messages.play-returning";
            String defaultMsg = firstTime
                    ? "§6Bienvenue dans votre monde Boxed ! Complétez vos quêtes pour agrandir votre zone."
                    : "§aReprise de votre monde Boxed !";
            String raw = plugin.getConfig().getString(msgKey, defaultMsg);
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(raw));
        });

        return true;
    }

    /**
     * Sends the player back to the configured lobby world.
     * The player must currently be in their own boxed world.
     *
     * @param sender command sender (must be a player)
     * @return {@code true} always
     */
    private boolean handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande ne peut être utilisée qu'en jeu.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("boxed.use")) {
            player.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!plugin.getWorldManager().isBoxedWorld(player.getWorld())) {
            player.sendMessage(Component.text("Vous n'êtes pas dans un monde Boxed.")
                    .color(NamedTextColor.YELLOW));
            return true;
        }

        plugin.getWorldManager().sendToLobby(player);

        String raw = plugin.getConfig().getString(
                "messages.leave-world", "§7Vous avez quitté votre monde Boxed.");
        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(raw));
        return true;
    }

    /**
     * Opens the quest GUI for the requesting player.
     *
     * @param sender command sender (must be a player)
     * @return {@code true} always
     */
    private boolean handleQuests(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande ne peut être utilisée qu'en jeu.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("boxed.use")) {
            player.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(Component.text("Erreur : données non chargées.")
                    .color(NamedTextColor.RED));
            return true;
        }
        new QuestMenuGUI(plugin, player, data).open();
        return true;
    }

    /**
     * Displays zone and progression info to the requesting player.
     *
     * @param sender command sender (must be a player)
     * @return {@code true} always
     */
    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Cette commande ne peut être utilisée qu'en jeu.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("boxed.use")) {
            player.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player.getUniqueId());
        if (data == null) {
            player.sendMessage(Component.text("Erreur : données non chargées.")
                    .color(NamedTextColor.RED));
            return true;
        }

        int chunks    = data.getZoneChunks();
        int blockSize = (int) BorderUtils.chunksToBlockSize(chunks);
        boolean inOwnWorld = plugin.getWorldManager().isBoxedWorld(player.getWorld())
                && player.getUniqueId().equals(plugin.getWorldManager().getWorldOwner(player.getWorld()));

        player.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        player.sendMessage(Component.text(" Boxed — Informations").color(NamedTextColor.GOLD));
        player.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));

        // World status row — clickable join button when player is in the lobby
        Component worldValue = inOwnWorld
                ? Component.text("En jeu ✔").color(NamedTextColor.GREEN)
                : Component.text("Hors de votre monde  ")
                        .color(NamedTextColor.GRAY)
                        .append(Component.text("[▶ Rejoindre]")
                                .color(NamedTextColor.AQUA)
                                .decorate(TextDecoration.BOLD)
                                .clickEvent(ClickEvent.runCommand("/boxed play"))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Cliquez pour rejoindre votre monde Boxed")
                                                .color(NamedTextColor.AQUA))));
        player.sendMessage(Component.text("Monde : ").color(NamedTextColor.YELLOW).append(worldValue));

        player.sendMessage(Component.text("Zone : ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(chunks + " chunk(s)  (" + blockSize + "×" + blockSize + " blocs)")
                        .color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Palier actuel : ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getCurrentTier()))
                        .color(NamedTextColor.WHITE)));
        player.sendMessage(Component.text("Quêtes complétées : ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getCompletedQuests().size()))
                        .color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("Quêtes actives : ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getActiveQuests().size()))
                        .color(NamedTextColor.AQUA)));
        player.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        return true;
    }

    /**
     * Forces a player's zone size to the given chunk count.
     *
     * @param sender command sender (must have {@code boxed.admin})
     * @param args   command arguments ({@code setzone <player> <size>})
     * @return {@code true} always
     */
    private boolean handleSetZone(CommandSender sender, String[] args) {
        if (!sender.hasPermission("boxed.admin")) {
            sender.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage : /boxed setzone <joueur> <taille>")
                    .color(NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Joueur introuvable : " + args[1])
                    .color(NamedTextColor.RED));
            return true;
        }

        int size;
        try {
            size = Integer.parseInt(args[2]);
            if (size < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Taille invalide. Entier positif attendu.")
                    .color(NamedTextColor.RED));
            return true;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Component.text("Données introuvables pour " + target.getName())
                    .color(NamedTextColor.RED));
            return true;
        }

        data.setZoneChunks(size);
        plugin.getZoneManager().applyBorder(target, data);
        plugin.getPlayerDataManager().savePlayerData(data);

        sender.sendMessage(Component.text("Zone de " + target.getName()
                + " définie à " + size + " chunk(s).").color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Votre zone a été définie à " + size
                + " chunk(s) par un administrateur.").color(NamedTextColor.GREEN));
        return true;
    }

    /**
     * Resets a player's full progression to default values.
     *
     * @param sender command sender (must have {@code boxed.admin})
     * @param args   command arguments ({@code reset <player>})
     * @return {@code true} always
     */
    private boolean handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("boxed.admin")) {
            sender.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage : /boxed reset <joueur>")
                    .color(NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("Joueur introuvable : " + args[1])
                    .color(NamedTextColor.RED));
            return true;
        }

        PlayerData freshData = plugin.getPlayerDataManager().reset(target);
        plugin.getZoneManager().applyBorder(target, freshData);

        sender.sendMessage(Component.text("Progression de " + target.getName()
                + " réinitialisée.").color(NamedTextColor.GREEN));
        target.sendMessage(Component.text("Votre progression Boxed a été réinitialisée.")
                .color(NamedTextColor.RED));
        return true;
    }

    /**
     * Reloads {@code config.yml} and {@code quests.yml} and re-applies borders
     * to all online players.
     *
     * @param sender command sender (must have {@code boxed.admin})
     * @return {@code true} always
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("boxed.admin")) {
            sender.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }

        plugin.reloadConfig();
        plugin.getQuestManager().loadQuests();
        plugin.getZoneManager().reapplyAll(plugin.getPlayerDataManager());

        int questCount = plugin.getQuestManager().getQuests().size();
        long worldCount = Bukkit.getWorlds().stream()
                .filter(w -> plugin.getWorldManager().isBoxedWorld(w)).count();
        sender.sendMessage(Component.text("UltimateBoxed rechargé — ").color(NamedTextColor.GREEN)
                .append(Component.text(questCount + " quête(s)").color(NamedTextColor.YELLOW))
                .append(Component.text(", ").color(NamedTextColor.GREEN))
                .append(Component.text(worldCount + " monde(s) actif(s)").color(NamedTextColor.YELLOW)));
        plugin.getLogger().info("Config reloaded by " + sender.getName()
                + " (" + questCount + " quests, " + worldCount + " worlds)");
        return true;
    }

    /**
     * Displays a server-side status dashboard for administrators.
     *
     * <p>Shows: template presence, loaded quest count, active boxed worlds,
     * players currently in their world, player data file count, and the
     * configured save intervals.</p>
     *
     * @param sender command sender (must have {@code boxed.admin})
     * @return {@code true} always
     */
    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("boxed.admin")) {
            sender.sendMessage(Component.text("Vous n'avez pas la permission.")
                    .color(NamedTextColor.RED));
            return true;
        }

        File templateDir = new File(plugin.getDataFolder(), "template");
        boolean templateOk = new File(templateDir, "level.dat").exists();

        long activeWorlds = Bukkit.getWorlds().stream()
                .filter(w -> plugin.getWorldManager().isBoxedWorld(w)).count();
        long playersInWorlds = Bukkit.getOnlinePlayers().stream()
                .filter(p -> plugin.getWorldManager().isBoxedWorld(p.getWorld())).count();
        int questCount = plugin.getQuestManager().getQuests().size();

        File dataDir = new File(plugin.getDataFolder(), "playerdata");
        File[] dataFiles = dataDir.listFiles((d, n) -> n.endsWith(".yml"));
        int dataCount = dataFiles != null ? dataFiles.length : 0;

        long saveDelay    = plugin.getConfig().getLong("optimization.save-delay-ticks",        100L) / 20;
        long autoSaveInt  = plugin.getConfig().getLong("optimization.auto-save-interval-ticks", 6000L) / 20;

        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" Boxed — Statut serveur").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        sender.sendMessage(statusRow("Template",
                templateOk ? "✔ Présente" : "✗ Manquante — /boxed play bloqué",
                templateOk ? NamedTextColor.GREEN : NamedTextColor.RED));
        sender.sendMessage(statusRow("Quêtes chargées", String.valueOf(questCount), NamedTextColor.WHITE));
        sender.sendMessage(statusRow("Mondes actifs",
                activeWorlds + "  (" + playersInWorlds + " joueur(s) en jeu)", NamedTextColor.WHITE));
        sender.sendMessage(statusRow("Données joueurs", dataCount + " fichier(s)", NamedTextColor.WHITE));
        sender.sendMessage(statusRow("Sauvegarde",
                "délai " + saveDelay + "s  /  auto toutes les " + autoSaveInt + "s",
                NamedTextColor.GRAY));
        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        return true;
    }

    private static Component statusRow(String key, String value, NamedTextColor valueColor) {
        return Component.text(key + " : ").color(NamedTextColor.YELLOW)
                .append(Component.text(value).color(valueColor));
    }

    // ─── Help ─────────────────────────────────────────────────────────────────

    /**
     * Sends the command help listing to the sender.
     *
     * @param sender the command sender
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text(" Boxed — Aide").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
        sender.sendMessage(cmd("/boxed play",          "Rejoindre votre monde Boxed"));
        sender.sendMessage(cmd("/boxed leave",         "Retourner au lobby"));
        sender.sendMessage(cmd("/boxed quests",        "Ouvrir le menu des quêtes"));
        sender.sendMessage(cmd("/boxed info",          "Voir vos informations de zone"));
        if (sender.hasPermission("boxed.admin")) {
            sender.sendMessage(cmd("/boxed setzone <joueur> <n>", "Définir la taille de zone"));
            sender.sendMessage(cmd("/boxed reset <joueur>",       "Réinitialiser la progression"));
            sender.sendMessage(cmd("/boxed reload",               "Recharger la configuration"));
            sender.sendMessage(cmd("/boxed status",               "Voir le statut du serveur"));
        }
        sender.sendMessage(Component.text("══════════════════").color(NamedTextColor.GOLD));
    }

    private static Component cmd(String usage, String description) {
        return Component.text(usage).color(NamedTextColor.YELLOW)
                .append(Component.text(" — " + description).color(NamedTextColor.GRAY));
    }
}
