package com.danielmcai.antiipbypass;

import com.danielmcai.antiipbypass.commands.AntiIPCommand;
import com.danielmcai.antiipbypass.listeners.PlayerLoginListener;
import com.danielmcai.antiipbypass.managers.DiscordWebhook;
import com.danielmcai.antiipbypass.managers.IPManager;
import com.danielmcai.antiipbypass.managers.VPNChecker;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class AntiIPBypass extends JavaPlugin {

    private static AntiIPBypass instance;

    private IPManager ipManager;
    private VPNChecker vpnChecker;
    private DiscordWebhook discordWebhook;
    private Logger pluginFileLogger;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        setupFileLogger();

        ipManager = new IPManager(this);
        vpnChecker = new VPNChecker(this);
        discordWebhook = new DiscordWebhook(this);

        getServer().getPluginManager().registerEvents(new PlayerLoginListener(this), this);

        AntiIPCommand command = new AntiIPCommand(this);
        getCommand("antiip").setExecutor(command);
        getCommand("antiip").setTabCompleter(command);

        getLogger().info("AntiIPBypass enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (ipManager != null) {
            ipManager.saveData();
        }
        if (pluginFileLogger != null) {
            for (java.util.logging.Handler h : pluginFileLogger.getHandlers()) {
                h.close();
            }
        }
        getLogger().info("AntiIPBypass disabled.");
    }

    private void setupFileLogger() {
        if (!getConfig().getBoolean("logging.file", true)) {
            return;
        }
        try {
            File logsDir = new File(getDataFolder(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            File logFile = new File(logsDir, "antiipbypass.log");
            pluginFileLogger = Logger.getLogger("AntiIPBypassFile");
            pluginFileLogger.setUseParentHandlers(false);
            FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath(), true);
            fileHandler.setFormatter(new SimpleFormatter());
            pluginFileLogger.addHandler(fileHandler);
        } catch (IOException e) {
            getLogger().warning("Could not set up file logger: " + e.getMessage());
        }
    }

    public void logToFile(String message) {
        if (pluginFileLogger != null) {
            pluginFileLogger.info(message);
        }
    }

    public static AntiIPBypass getInstance() {
        return instance;
    }

    public IPManager getIPManager() {
        return ipManager;
    }

    public VPNChecker getVPNChecker() {
        return vpnChecker;
    }

    public DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }
}
