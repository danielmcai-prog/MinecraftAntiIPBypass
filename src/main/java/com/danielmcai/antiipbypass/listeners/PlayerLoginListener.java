package com.danielmcai.antiipbypass.listeners;

import com.danielmcai.antiipbypass.AntiIPBypass;
import com.danielmcai.antiipbypass.managers.IPManager;
import com.danielmcai.antiipbypass.managers.VPNChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles player login events.
 *
 * Fires on {@link AsyncPlayerPreLoginEvent} (already async) so the VPN lookup
 * is done synchronously within the event — this is acceptable because
 * AsyncPlayerPreLoginEvent runs off the main thread.  However, to avoid holding
 * up the login for too long, the VPN check has its own read timeout (configurable).
 */
public class PlayerLoginListener implements Listener {

    private final AntiIPBypass plugin;

    public PlayerLoginListener(AntiIPBypass plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        String uuid = event.getUniqueId().toString();
        String ip = event.getAddress().getHostAddress();

        // 1. Record the login and detect IP events
        IPManager.LoginResult result = plugin.getIPManager().recordLogin(uuid, playerName, ip);

        // 2. Log to console / file
        logLoginInfo(result);

        // 3. Dispatch notifications
        dispatchNotifications(result);

        // 4. VPN check (synchronous within the async event)
        // Note: the antiipbypass.bypass permission cannot be evaluated here because
        // AsyncPlayerPreLoginEvent fires before the player is fully loaded.  Use the
        // vpn-detection.bypass-players list in config.yml for per-player bypasses.
        if (plugin.getConfig().getBoolean("vpn-detection.enabled", true)
                && !isBypassed(uuid, playerName)) {
            runVPNCheck(event, playerName, ip);
        }
    }

    // -----------------------------------------------------------------------
    // VPN check
    // -----------------------------------------------------------------------

    private void runVPNCheck(AsyncPlayerPreLoginEvent event, String playerName, String ip) {
        try {
            VPNChecker.VPNResult vpnResult = plugin.getVPNChecker().checkAsync(ip).get();

            String consoleTag = "[AntiIPBypass]";

            if (vpnResult.suspected) {
                String type = vpnResult.proxy ? "Proxy/VPN" : "Hosting/Datacenter";
                String logMsg = String.format("%s VPN detected for %s (%s) — type: %s, ISP: %s",
                        consoleTag, playerName, ip, type,
                        vpnResult.isp != null ? vpnResult.isp : "Unknown");

                plugin.getLogger().warning(logMsg);
                plugin.logToFile(logMsg);

                // Notify admins in-game (schedule on main thread)
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.broadcast(
                                Component.text(consoleTag + " " + playerName + " (" + ip + ") may be using a VPN!",
                                        NamedTextColor.RED),
                                "antiipbypass.admin"));

                // Send Discord notification
                plugin.getDiscordWebhook().notifyVPN(playerName, ip, vpnResult);

                // Apply configured action
                String action = plugin.getConfig().getString("vpn-detection.action", "LOG").toUpperCase();
                if ("KICK".equals(action)) {
                    String rawMsg = plugin.getConfig().getString(
                            "vpn-detection.kick-message",
                            "&cYou are not allowed to connect using a VPN or proxy.");
                    Component kickMsg = LegacyComponentSerializer.legacyAmpersand().deserialize(rawMsg);
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, kickMsg);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[AntiIPBypass] VPN check failed for " + playerName + ": " + e.getMessage());
        }
    }

    /**
     * Returns true if the player should skip VPN checks.
     * Uses the {@code vpn-detection.bypass-players} list in config.yml because
     * permission checks are unavailable at AsyncPlayerPreLoginEvent time.
     */
    private boolean isBypassed(String uuid, String playerName) {
        java.util.List<String> bypassList =
                plugin.getConfig().getStringList("vpn-detection.bypass-players");
        return bypassList.stream().anyMatch(entry ->
                entry.equalsIgnoreCase(playerName) || entry.equalsIgnoreCase(uuid));
    }

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    private void logLoginInfo(IPManager.LoginResult result) {
        boolean consoleLog = plugin.getConfig().getBoolean("logging.console", true);

        String base = String.format("[AntiIPBypass] %s logged in with IP %s",
                result.playerName, result.ip);

        if (result.isNewIP && result.ipSwitched) {
            String msg = base + " (new IP — previously used " + result.previousIP + ")";
            if (consoleLog) plugin.getLogger().info(msg);
            plugin.logToFile(msg);
        } else if (result.isNewIP) {
            String msg = base + " (first login recorded)";
            if (consoleLog) plugin.getLogger().info(msg);
            plugin.logToFile(msg);
        } else {
            if (consoleLog) plugin.getLogger().info(base + " (known IP)");
            plugin.logToFile(base + " (known IP)");
        }

        if (!result.duplicateUUIDs.isEmpty()) {
            Set<String> otherNames = result.duplicateUUIDs.stream()
                    .map(uid -> plugin.getIPManager().getNameForUUID(uid))
                    .collect(Collectors.toSet());
            String msg = "[AntiIPBypass] Duplicate IP: " + result.ip
                    + " is also used by: " + String.join(", ", otherNames);
            if (consoleLog) plugin.getLogger().warning(msg);
            plugin.logToFile(msg);
        }
    }

    // -----------------------------------------------------------------------
    // Notification dispatch
    // -----------------------------------------------------------------------

    private void dispatchNotifications(IPManager.LoginResult result) {
        Set<String> otherNames = result.duplicateUUIDs.stream()
                .map(uid -> plugin.getIPManager().getNameForUUID(uid))
                .collect(Collectors.toSet());

        // New IP / IP switch
        if (result.ipSwitched) {
            plugin.getDiscordWebhook().notifyIPSwitch(
                    result.playerName, result.previousIP, result.ip, null);

            if (plugin.getConfig().getBoolean("tracking.warn-admins-ip-switch", true)) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.broadcast(
                                Component.text("[AntiIPBypass] " + result.playerName
                                        + " switched IP: " + result.previousIP + " → " + result.ip,
                                        NamedTextColor.YELLOW),
                                "antiipbypass.admin"));
            }
        } else if (result.isNewIP) {
            plugin.getDiscordWebhook().notifyNewIP(result.playerName, result.ip, null);
        }

        // Duplicate IP
        if (!result.duplicateUUIDs.isEmpty()) {
            plugin.getDiscordWebhook().notifyDuplicateIP(result.playerName, result.ip, otherNames);

            if (plugin.getConfig().getBoolean("tracking.warn-admins-duplicate", true)) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        Bukkit.broadcast(
                                Component.text("[AntiIPBypass] Duplicate IP " + result.ip
                                        + " — also used by: " + String.join(", ", otherNames),
                                        NamedTextColor.GOLD),
                                "antiipbypass.admin"));
            }
        }
    }
}
