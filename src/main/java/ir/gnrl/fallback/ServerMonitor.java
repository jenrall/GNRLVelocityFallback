package ir.gnrl.fallback;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ServerMonitor {

    private final VelocityFallback plugin;
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final Logger logger;

    private ScheduledTask monitorTask;

    public ServerMonitor(VelocityFallback plugin, ProxyServer server, ConfigManager configManager) {
        this.plugin = plugin;
        this.server = server;
        this.configManager = configManager;
        this.logger = plugin.getLogger();
    }

    public void start() {
        int interval = configManager.getCheckInterval();

        monitorTask = server.getScheduler()
                .buildTask(plugin, this::checkAllServers)
                .repeat(interval, TimeUnit.SECONDS)
                .schedule();

        logger.info("Server monitor started (checking every {}s)", interval);
    }

    public void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
    }

    /* ---------------- Main check loop ---------------- */

    private void checkAllServers() {
        for (String serverName : configManager.getFallbackChains().keySet()) {
            checkServer(serverName);
        }
    }

    private void checkServer(String serverName) {
        Optional<RegisteredServer> optServer = server.getServer(serverName);
        if (optServer.isEmpty()) {
            logger.warn("Server '{}' is not registered in Velocity", serverName);
            return;
        }

        RegisteredServer registeredServer = optServer.get();
        long startTime = System.currentTimeMillis();

        // پینگ سرور — latency رو خودمون محاسبه می‌کنیم
        registeredServer.ping().whenComplete((ping, error) -> {
            long latency = System.currentTimeMillis() - startTime;

            if (error != null || ping == null) {
                logger.warn("Server '{}' is offline — redirecting players", serverName);
                redirectAllPlayers(serverName);
                return;
            }

            if (latency > configManager.getMaxPing()) {
                logger.warn("Server '{}' has high ping ({}ms). Redirecting players...",
                        serverName, latency);
                redirectAllPlayers(serverName);
            }
        });
    }

    /* ---------------- Redirect logic ---------------- */

    private void redirectAllPlayers(String fromServerName) {
        List<String> fallbacks = configManager.getFallbackFor(fromServerName);
        if (fallbacks.isEmpty()) {
            logger.warn("No fallback configured for server '{}'", fromServerName);
            return;
        }

        Optional<RegisteredServer> optFrom = server.getServer(fromServerName);
        if (optFrom.isEmpty()) return;

        RegisteredServer fromServer = optFrom.get();

        for (Player player : fromServer.getPlayersConnected()) {
            sendToBestFallback(player, fallbacks);
        }
    }

    private void sendToBestFallback(Player player, List<String> fallbacks) {
        for (String fbName : fallbacks) {
            String trimmed = fbName.trim();
            Optional<RegisteredServer> optFb = server.getServer(trimmed);
            if (optFb.isEmpty()) continue;

            RegisteredServer fallback = optFb.get();

            fallback.ping().whenComplete((ping, error) -> {
                if (error == null && ping != null) {
                    player.createConnectionRequest(fallback).connect().whenComplete((result, err) -> {
                        if (err == null && result != null && result.isSuccessful()) {
                            player.sendMessage(
                                    Component.text("⚠ Server is unavailable. You were moved to " + trimmed)
                                            .color(NamedTextColor.YELLOW));
                            logger.info("Moved {} to {}", player.getUsername(), trimmed);
                        } else {
                            logger.warn("Failed to move {} to {}", player.getUsername(), trimmed);
                        }
                    });
                }
            });

            // فقط اولین fallback رو امتحان کن
            return;
        }
    }
}
