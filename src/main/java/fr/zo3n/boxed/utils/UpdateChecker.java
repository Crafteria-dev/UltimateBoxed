package fr.zo3n.boxed.utils;

import fr.zo3n.boxed.BoxedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Checks the latest GitHub release of UltimateBoxed and compares it with the
 * version declared in {@code plugin.yml}.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>The check runs once on {@link BoxedPlugin#onEnable()} on an async thread
 *       so it never blocks the main thread or delays server startup.</li>
 *   <li>When an update is found: a warning is printed to the console and every
 *       online administrator receives a clickable in-game notification.</li>
 *   <li>Administrators who join <em>after</em> the check also receive the
 *       notification via {@link #notifyIfOutdated(Player)}.</li>
 *   <li>The check can be disabled in {@code config.yml} with
 *       {@code update-check: false}.</li>
 * </ul>
 */
public class UpdateChecker {

    private static final String REPO         = "Crafteria-dev/UltimateBoxed";
    private static final String API_URL      = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String DOWNLOAD_URL = "https://github.com/" + REPO + "/releases/latest";

    private final BoxedPlugin plugin;
    private final String      currentVersion;

    /**
     * Set to the latest release version string once the async check finds a
     * newer release.  {@code null} means up-to-date (or check not yet done).
     * Declared {@code volatile} so that the main thread always sees the value
     * written by the async check thread.
     */
    private volatile String latestVersion = null;

    /**
     * Creates a new {@link UpdateChecker}.
     *
     * @param plugin the owning plugin instance
     */
    public UpdateChecker(BoxedPlugin plugin) {
        this.plugin         = plugin;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Schedules the GitHub version check on a Bukkit async thread.
     * Does nothing if {@code update-check: false} is set in {@code config.yml}.
     */
    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("update-check", true)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::performCheck);
    }

    /**
     * Sends an update notification to the given player if a newer version was
     * found by the last {@link #checkAsync()} call.
     * Safe to call for every admin on join — does nothing when the plugin is
     * up-to-date or the check has not yet completed.
     *
     * @param player the administrator to notify
     */
    public void notifyIfOutdated(Player player) {
        String latest = latestVersion; // volatile read
        if (latest == null) return;

        player.sendMessage(Component.text("══════════════════════════").color(NamedTextColor.GOLD));
        player.sendMessage(
                Component.text("UltimateBoxed — Mise à jour disponible !")
                        .color(NamedTextColor.YELLOW));
        player.sendMessage(
                Component.text("  Version actuelle : ").color(NamedTextColor.GRAY)
                        .append(Component.text("v" + currentVersion).color(NamedTextColor.RED)));
        player.sendMessage(
                Component.text("  Nouvelle version  : ").color(NamedTextColor.GRAY)
                        .append(Component.text("v" + latest).color(NamedTextColor.GREEN)));
        player.sendMessage(
                Component.text("  Télécharger : ").color(NamedTextColor.GRAY)
                        .append(Component.text("[Ouvrir sur GitHub]")
                                .color(NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(DOWNLOAD_URL))
                                .hoverEvent(HoverEvent.showText(
                                        Component.text("Ouvrir le navigateur")
                                                .color(NamedTextColor.GRAY)))));
        player.sendMessage(Component.text("══════════════════════════").color(NamedTextColor.GOLD));
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    /** Performs the HTTP request and version comparison on the current thread. */
    private void performCheck() {
        try {
            HttpURLConnection conn = openConnection();
            int code = conn.getResponseCode();

            if (code == 404) {
                // Repository has no releases yet — not an error
                plugin.getLogger().fine("Update check: aucune release trouvée sur GitHub.");
                return;
            }
            if (code != 200) {
                plugin.getLogger().warning(
                        "Vérification de mise à jour impossible (HTTP " + code + ").");
                return;
            }

            String body   = readResponse(conn);
            String tag    = extractTagName(body);
            if (tag == null) {
                plugin.getLogger().warning(
                        "Réponse GitHub illisible lors de la vérification de mise à jour.");
                return;
            }

            // Normalise: strip leading 'v' or 'V' (e.g. "v1.2.3" → "1.2.3")
            String latest = (tag.startsWith("v") || tag.startsWith("V"))
                    ? tag.substring(1) : tag;

            // Strip -SNAPSHOT suffix from current version for comparison
            String current = currentVersion.replace("-SNAPSHOT", "");

            if (current.equals(latest)) {
                plugin.getLogger().info(
                        "UltimateBoxed est à jour (v" + currentVersion + ").");
            } else {
                latestVersion = latest; // volatile write — visible to main thread

                plugin.getLogger().warning("=== UltimateBoxed — Mise à jour disponible ! ===");
                plugin.getLogger().warning("  Version actuelle : v" + currentVersion);
                plugin.getLogger().warning("  Nouvelle version : v" + latest);
                plugin.getLogger().warning("  Téléchargez : " + DOWNLOAD_URL);
                plugin.getLogger().warning("================================================");

                // Notify any administrators already in-game (switch to main thread)
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.getServer().getOnlinePlayers().stream()
                                .filter(p -> p.hasPermission("boxed.admin"))
                                .forEach(this::notifyIfOutdated));
            }

        } catch (IOException e) {
            plugin.getLogger().warning(
                    "Impossible de vérifier les mises à jour : " + e.getMessage());
        }
    }

    private static HttpURLConnection openConnection() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(API_URL).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent",  "UltimateBoxed-UpdateChecker");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Extracts the {@code tag_name} value from a GitHub releases/latest JSON
     * response without requiring an external JSON library.
     *
     * @param json raw JSON string
     * @return the tag value, or {@code null} if not found
     */
    private static String extractTagName(String json) {
        final String KEY = "\"tag_name\":";
        int idx = json.indexOf(KEY);
        if (idx < 0) return null;

        int pos = idx + KEY.length();
        // skip optional whitespace
        while (pos < json.length() && json.charAt(pos) == ' ') pos++;
        // expect opening quote
        if (pos >= json.length() || json.charAt(pos) != '"') return null;
        pos++;
        int end = json.indexOf('"', pos);
        return end > pos ? json.substring(pos, end) : null;
    }
}
