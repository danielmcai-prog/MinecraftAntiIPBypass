package com.danielmcai.antiipbypass.commands;

import com.danielmcai.antiipbypass.AntiIPBypass;
import com.danielmcai.antiipbypass.managers.VPNChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides the {@code /antiip} command with sub-commands:
 * <ul>
 *   <li>{@code reload} — reloads the config</li>
 *   <li>{@code lookup <player>} — shows all IPs recorded for a player</li>
 *   <li>{@code checkip <ip>} — manually triggers a VPN check on an IP</li>
 * </ul>
 */
public class AntiIPCommand implements CommandExecutor, TabCompleter {

    private final AntiIPBypass plugin;

    public AntiIPCommand(AntiIPBypass plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antiipbypass.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "lookup" -> handleLookup(sender, args);
            case "checkip" -> handleCheckIP(sender, args);
            default -> sendHelp(sender, label);
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Sub-command handlers
    // -----------------------------------------------------------------------

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getIPManager().loadData();
        sender.sendMessage(Component.text("[AntiIPBypass] Configuration reloaded.", NamedTextColor.GREEN));
    }

    @SuppressWarnings("deprecation")
    private void handleLookup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antiip lookup <player>", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];

        // Prefer data already stored locally to avoid creating ghost offline profiles.
        String uuid = plugin.getIPManager().getUUIDForName(playerName);
        if (uuid == null) {
            // Fall back to Bukkit's offline player lookup only if the name was never
            // seen before (hasPlayedBefore guard prevents ghost profile creation).
            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (!offlinePlayer.hasPlayedBefore()) {
                sender.sendMessage(Component.text("[AntiIPBypass] No IP records found for " + playerName + ".", NamedTextColor.YELLOW));
                return;
            }
            uuid = offlinePlayer.getUniqueId().toString();
        }

        List<String> ips = plugin.getIPManager().getIPsForPlayer(uuid);
        if (ips.isEmpty()) {
            sender.sendMessage(Component.text("[AntiIPBypass] No IP records found for " + playerName + ".", NamedTextColor.YELLOW));
            return;
        }

        final String finalUuid = uuid;
        sender.sendMessage(Component.text("--- AntiIPBypass: IPs for " + playerName + " ---", NamedTextColor.AQUA));
        for (String ip : ips) {
            // Find other players sharing this IP
            Set<String> others = plugin.getIPManager().getPlayersForIP(ip).stream()
                    .filter(u -> !u.equals(finalUuid))
                    .map(u -> plugin.getIPManager().getNameForUUID(u))
                    .collect(Collectors.toSet());
            String sharedWith = others.isEmpty() ? "" : " (shared with: " + String.join(", ", others) + ")";
            sender.sendMessage(Component.text("  " + ip + sharedWith, NamedTextColor.WHITE));
        }
    }

    private void handleCheckIP(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /antiip checkip <ip>", NamedTextColor.RED));
            return;
        }
        String ip = args[1];
        sender.sendMessage(Component.text("[AntiIPBypass] Checking " + ip + " ...", NamedTextColor.GRAY));

        plugin.getVPNChecker().checkAsync(ip).thenAccept(result -> {
            // Schedule back to main thread for message sending
            Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Component.text("--- VPN Check: " + ip + " ---", NamedTextColor.AQUA));
                sender.sendMessage(Component.text("Proxy/VPN: " + result.proxy, result.proxy ? NamedTextColor.RED : NamedTextColor.GREEN));
                sender.sendMessage(Component.text("Hosting/DC: " + result.hosting, result.hosting ? NamedTextColor.RED : NamedTextColor.GREEN));
                sender.sendMessage(Component.text("ISP: " + (result.isp != null ? result.isp : "Unknown"), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Org: " + (result.org != null ? result.org : "Unknown"), NamedTextColor.WHITE));
                sender.sendMessage(Component.text("Suspected VPN: " + result.suspected,
                        result.suspected ? NamedTextColor.RED : NamedTextColor.GREEN));

                // Also list accounts that have used this IP
                Set<String> players = plugin.getIPManager().getPlayersForIP(ip);
                if (!players.isEmpty()) {
                    Set<String> names = players.stream()
                            .map(u -> plugin.getIPManager().getNameForUUID(u))
                            .collect(Collectors.toSet());
                    sender.sendMessage(Component.text("Known accounts: " + String.join(", ", names), NamedTextColor.YELLOW));
                }
            });
        });
    }

    // -----------------------------------------------------------------------
    // Help
    // -----------------------------------------------------------------------

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("--- AntiIPBypass Commands ---", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.WHITE)
                .append(Component.text(" - Reload config", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " lookup <player>", NamedTextColor.WHITE)
                .append(Component.text(" - Show IPs for a player", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " checkip <ip>", NamedTextColor.WHITE)
                .append(Component.text(" - Check if an IP is a VPN", NamedTextColor.GRAY)));
    }

    // -----------------------------------------------------------------------
    // Tab completion
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("antiipbypass.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return filterStart(List.of("reload", "lookup", "checkip"), args[0]);
        }
        if (args.length == 2 && "lookup".equalsIgnoreCase(args[0])) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(p -> p.getName())
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private List<String> filterStart(List<String> options, String prefix) {
        return options.stream()
                .filter(o -> o.startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
