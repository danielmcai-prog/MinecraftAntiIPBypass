package com.danielmcai.antiipbypass.managers;

import com.danielmcai.antiipbypass.AntiIPBypass;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Checks whether an IP address belongs to a VPN, proxy, or hosting provider.
 *
 * Uses ip-api.com (free tier, no API key required) which returns proxy/hosting
 * flags.  The result is fetched asynchronously so it never blocks the main thread.
 *
 * Note: the free tier of ip-api.com requires plain HTTP; HTTPS is only available
 * on paid plans.  IP addresses are therefore transmitted in cleartext to ip-api.com.
 * Upgrade to a paid tier or switch to an HTTPS-capable provider if data-in-transit
 * privacy is a concern.
 *
 * API endpoint: http://ip-api.com/json/{ip}?fields=status,proxy,hosting,isp,org,query
 */
public class VPNChecker {

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,proxy,hosting,isp,org,query";

    private final AntiIPBypass plugin;

    public VPNChecker(AntiIPBypass plugin) {
        this.plugin = plugin;
    }

    /**
     * Asynchronously checks whether the given IP is a VPN / proxy / hosting address.
     *
     * @param ip the IP address to check
     * @return a CompletableFuture that resolves to a {@link VPNResult}
     */
    public CompletableFuture<VPNResult> checkAsync(String ip) {
        return CompletableFuture.supplyAsync(() -> check(ip))
                .orTimeout(plugin.getConfig().getLong("vpn-detection.check-timeout", 5000L),
                           TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("VPN check timed out or failed for " + ip + ": " + ex.getMessage());
                    return new VPNResult(ip, false, false, null, null, false);
                });
    }

    private VPNResult check(String ip) {
        try {
            String urlStr = String.format(API_URL, ip);
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "AntiIPBypass/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                plugin.getLogger().warning("ip-api.com returned HTTP " + responseCode + " for " + ip);
                return new VPNResult(ip, false, false, null, null, false);
            }

            try (InputStreamReader reader = new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String status = json.has("status") ? json.get("status").getAsString() : "fail";
                if (!"success".equals(status)) {
                    return new VPNResult(ip, false, false, null, null, false);
                }
                boolean proxy = json.has("proxy") && json.get("proxy").getAsBoolean();
                boolean hosting = json.has("hosting") && json.get("hosting").getAsBoolean();
                String isp = json.has("isp") ? json.get("isp").getAsString() : null;
                String org = json.has("org") ? json.get("org").getAsString() : null;
                boolean suspected = proxy || hosting;
                return new VPNResult(ip, proxy, hosting, isp, org, suspected);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("VPN check failed for " + ip + ": " + e.getMessage());
            return new VPNResult(ip, false, false, null, null, false);
        }
    }

    // -----------------------------------------------------------------------
    // Result class
    // -----------------------------------------------------------------------

    public static class VPNResult {
        public final String ip;
        public final boolean proxy;
        public final boolean hosting;
        public final String isp;
        public final String org;
        public final boolean suspected;

        public VPNResult(String ip, boolean proxy, boolean hosting,
                         String isp, String org, boolean suspected) {
            this.ip = ip;
            this.proxy = proxy;
            this.hosting = hosting;
            this.isp = isp;
            this.org = org;
            this.suspected = suspected;
        }
    }
}
