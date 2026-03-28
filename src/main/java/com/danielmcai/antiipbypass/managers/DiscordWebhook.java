package com.danielmcai.antiipbypass.managers;

import com.danielmcai.antiipbypass.AntiIPBypass;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

/**
 * Sends structured embed messages to a configured Discord webhook.
 *
 * All network calls are made asynchronously so they never block the main thread.
 */
public class DiscordWebhook {

    private final AntiIPBypass plugin;

    public DiscordWebhook(AntiIPBypass plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public notification methods
    // -----------------------------------------------------------------------

    /** Notify that a player logged in with a brand-new (first-seen) IP. */
    public void notifyNewIP(String playerName, String ip, String isp) {
        if (!shouldNotify("new-ip")) return;
        String description = String.format(
                "**Player:** `%s`\n**IP:** `%s`\n**ISP:** `%s`\n**Event:** First login from this IP",
                playerName, ip, isp == null ? "Unknown" : isp);
        sendEmbed("New IP Address Detected", description, 0x3498db);
    }

    /** Notify that a player switched to a different IP since last login. */
    public void notifyIPSwitch(String playerName, String previousIP, String newIP, String isp) {
        if (!shouldNotify("ip-switch")) return;
        String description = String.format(
                "**Player:** `%s`\n**Previous IP:** `%s`\n**New IP:** `%s`\n**ISP:** `%s`\n**Event:** IP address changed",
                playerName, previousIP, newIP, isp == null ? "Unknown" : isp);
        sendEmbed("IP Switch Detected", description, 0xf39c12);
    }

    /** Notify that multiple accounts share the same IP. */
    public void notifyDuplicateIP(String playerName, String ip, Set<String> otherPlayers) {
        if (!shouldNotify("duplicate-ip")) return;
        String others = otherPlayers.isEmpty() ? "None" : String.join(", ", otherPlayers);
        String description = String.format(
                "**Player:** `%s`\n**IP:** `%s`\n**Other accounts on this IP:** `%s`\n**Event:** Duplicate IP detected",
                playerName, ip, others);
        sendEmbed("Duplicate IP Detected", description, 0xe67e22);
    }

    /** Notify that a VPN / proxy was detected. */
    public void notifyVPN(String playerName, String ip, VPNChecker.VPNResult result) {
        if (!shouldNotify("vpn-detected")) return;
        String type = result.proxy ? "Proxy/VPN" : (result.hosting ? "Hosting/Datacenter" : "Suspected");
        String description = String.format(
                "**Player:** `%s`\n**IP:** `%s`\n**Type:** `%s`\n**ISP:** `%s`\n**Org:** `%s`\n**Event:** Suspected VPN/Proxy connection",
                playerName, ip, type,
                result.isp == null ? "Unknown" : result.isp,
                result.org == null ? "Unknown" : result.org);
        sendEmbed("⚠ VPN / Proxy Detected", description, 0xe74c3c);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private boolean shouldNotify(String key) {
        return plugin.getConfig().getBoolean("discord.enabled", false)
                && plugin.getConfig().getBoolean("discord.notify." + key, true);
    }

    private void sendEmbed(String title, String description, int color) {
        String webhookUrl = plugin.getConfig().getString("discord.webhook-url", "");
        if (webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK")) {
            return;
        }
        String webhookUsername = plugin.getConfig().getString("discord.username", "AntiIPBypass");
        String avatarUrl = plugin.getConfig().getString("discord.avatar-url", "");

        // Build JSON payload
        String timestamp = Instant.now().toString();
        String avatarField = avatarUrl.isEmpty() ? "" : String.format(", \"avatar_url\": \"%s\"", avatarUrl);
        String escapedDesc = escapeJson(description);
        String escapedTitle = escapeJson(title);

        String payload = String.format(
                "{\"username\": \"%s\"%s, \"embeds\": [{\"title\": \"%s\", "
                + "\"description\": \"%s\", \"color\": %d, \"timestamp\": \"%s\"}]}",
                escapeJson(webhookUsername), avatarField, escapedTitle, escapedDesc, color, timestamp);

        // Send asynchronously
        String finalWebhookUrl = webhookUrl;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                URL url = new URL(finalWebhookUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "AntiIPBypass/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    plugin.getLogger().warning("Discord webhook returned HTTP " + responseCode);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
            }
        });
    }

    /** Minimal JSON string escaping. */
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
