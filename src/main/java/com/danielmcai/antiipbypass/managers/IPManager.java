package com.danielmcai.antiipbypass.managers;

import com.danielmcai.antiipbypass.AntiIPBypass;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Manages IP tracking: stores player-to-IP mappings, detects duplicate IPs
 * (multiple accounts sharing the same IP) and IP switches (one account
 * connecting from a different address than before).
 */
public class IPManager {

    private final AntiIPBypass plugin;
    private final File dataFile;
    private YamlConfiguration data;

    // In-memory maps built from the YAML on load
    // uuid -> ordered list of IPs (most recent last)
    private final Map<String, List<String>> playerIPs = new HashMap<>();
    // ip -> set of UUIDs that have used it
    private final Map<String, Set<String>> ipPlayers = new HashMap<>();
    // uuid -> player name (for display)
    private final Map<String, String> uuidToName = new HashMap<>();

    public IPManager(AntiIPBypass plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "ip_data.yml");
        loadData();
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    public void loadData() {
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            data = new YamlConfiguration();
            return;
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        // Load player -> IPs
        if (data.isConfigurationSection("players")) {
            for (String uuid : data.getConfigurationSection("players").getKeys(false)) {
                List<String> ips = data.getStringList("players." + uuid + ".ips");
                String name = data.getString("players." + uuid + ".name", uuid);
                playerIPs.put(uuid, new ArrayList<>(ips));
                uuidToName.put(uuid, name);
            }
        }

        // Rebuild reverse map
        for (Map.Entry<String, List<String>> entry : playerIPs.entrySet()) {
            for (String ip : entry.getValue()) {
                ipPlayers.computeIfAbsent(ip, k -> new HashSet<>()).add(entry.getKey());
            }
        }
    }

    public void saveData() {
        data = new YamlConfiguration();
        for (Map.Entry<String, List<String>> entry : playerIPs.entrySet()) {
            String uuid = entry.getKey();
            data.set("players." + uuid + ".ips", entry.getValue());
            data.set("players." + uuid + ".name", uuidToName.getOrDefault(uuid, uuid));
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save ip_data.yml: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Recording
    // -----------------------------------------------------------------------

    /**
     * Records a login event. Returns a {@link LoginResult} describing what
     * was detected (new IP, known IP, duplicate IP on different account, etc.).
     */
    public LoginResult recordLogin(String uuid, String playerName, String ip) {
        uuidToName.put(uuid, playerName);

        List<String> knownIPs = playerIPs.computeIfAbsent(uuid, k -> new ArrayList<>());
        Set<String> knownUsers = ipPlayers.computeIfAbsent(ip, k -> new HashSet<>());

        boolean newIP = !knownIPs.contains(ip);
        boolean ipSwitched = newIP && !knownIPs.isEmpty();
        String previousIP = knownIPs.isEmpty() ? null : knownIPs.get(knownIPs.size() - 1);

        // Other accounts that share this IP (excluding this player)
        Set<String> duplicateUUIDs = new HashSet<>(knownUsers);
        duplicateUUIDs.remove(uuid);

        // Update records
        if (newIP) {
            int maxIPs = plugin.getConfig().getInt("tracking.max-ips-per-player", 10);
            if (knownIPs.size() >= maxIPs) {
                knownIPs.remove(0);
            }
            knownIPs.add(ip);
        }
        knownUsers.add(uuid);

        // Persist immediately (async-safe since Bukkit YAML is synchronous)
        saveData();

        return new LoginResult(uuid, playerName, ip, newIP, ipSwitched, previousIP, duplicateUUIDs);
    }

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /** Returns all IPs known for a player UUID. */
    public List<String> getIPsForPlayer(String uuid) {
        return Collections.unmodifiableList(playerIPs.getOrDefault(uuid, Collections.emptyList()));
    }

    /** Returns all player UUIDs known to have used a given IP. */
    public Set<String> getPlayersForIP(String ip) {
        return Collections.unmodifiableSet(ipPlayers.getOrDefault(ip, Collections.emptySet()));
    }

    /** Returns the stored display name for a UUID, or the UUID itself if unknown. */
    public String getNameForUUID(String uuid) {
        return uuidToName.getOrDefault(uuid, uuid);
    }

    /**
     * Looks up a player UUID by name from the locally-stored data.
     * Returns {@code null} if no matching entry is found.
     */
    public String getUUIDForName(String name) {
        for (Map.Entry<String, String> entry : uuidToName.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Inner result class
    // -----------------------------------------------------------------------

    public static class LoginResult {
        public final String uuid;
        public final String playerName;
        public final String ip;
        public final boolean isNewIP;
        public final boolean ipSwitched;
        public final String previousIP;
        public final Set<String> duplicateUUIDs;

        public LoginResult(String uuid, String playerName, String ip,
                           boolean isNewIP, boolean ipSwitched,
                           String previousIP, Set<String> duplicateUUIDs) {
            this.uuid = uuid;
            this.playerName = playerName;
            this.ip = ip;
            this.isNewIP = isNewIP;
            this.ipSwitched = ipSwitched;
            this.previousIP = previousIP;
            this.duplicateUUIDs = duplicateUUIDs;
        }
    }
}
