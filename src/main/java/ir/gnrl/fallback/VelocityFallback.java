package ir.gnrl.fallback;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "velocityfallback",
        name = "VelocityFallback",
        version = "1.0.0",
        description = "Auto-redirect players to lobby when backend server is down or laggy",
        authors = {"GNRl"}
)
public class VelocityFallback {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigManager configManager;
    private ServerMonitor serverMonitor;

    @Inject
    public VelocityFallback(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // لود کانفیگ
        this.configManager = new ConfigManager(this, dataDirectory);
        configManager.load();

        // شروع مانیتور سرورها
        this.serverMonitor = new ServerMonitor(this, server, configManager);
        serverMonitor.start();

        logger.info("✔ VelocityFallback enabled — monitoring backend servers");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (serverMonitor != null) {
            serverMonitor.stop();
        }
        logger.info("✖ VelocityFallback disabled");
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}
