package ir.gnrl.fallback;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ServerMonitor {

    private final VelocityFallback plugin;
    private final ProxyServer server;
    private final ConfigManager configManager;
    private final Logger logger;

    private ScheduledTask monitorTask;

    private final Map<String, Long> lastFallbackTime = new HashMap<>();
    private static final long FALLBACK_COOLDOWN_MS = 30_000;

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

    /* ==========================================================
       🎯 KickedFromServerEvent — کیک شدن از سرور بک‌اند
       ========================================================== */
    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        String kickedFrom = event.getServer().getServerInfo().getName();
        Player player = event.getPlayer();

        logger.info("Player {} kicked from '{}'", player.getUsername(), kickedFrom);

        // چک کن این سرور توی لیست ما هست
        List<String> fallbacks = configManager.getFallbackFor(kickedFrom);
        if (fallbacks.isEmpty()) {
            logger.info("No fallback configured for '{}'", kickedFrom);
            return;
        }

        // اولین fallback آنلاین رو پیدا کن
        for (String fbName : fallbacks) {
            String trimmed = fbName.trim();
            Optional<RegisteredServer> optFb = server.getServer(trimmed);
            if (optFb.isEmpty()) {
                logger.warn("Fallback '{}' not found", trimmed);
                continue;
            }

            RegisteredServer fallback = optFb.get();

            // نتیجه رو به Velocity بگو: بازیکن رو به این سرور بفرست
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                    fallback,
                    Component.text("⚠ Server '" + kickedFrom + "' is unavailable. Moving to " + trimmed)
                            .color(NamedTextColor.YELLOW)
            ));

            logger.info("Redirecting {} to '{}'", player.getUsername(), trimmed);
            return;
        }

        logger.warn("No online fallback available for '{}'", kickedFrom);
    }

    /* ==========================================================
       مانیتور دوره‌ای پینگ
       ========================================================== */

    private void checkAllServers() {
        for (String serverName : configManager.getFallbackChains().keySet()) {
            checkServer(serverName);
        }
    }

    private void checkServer(String serverName) {
        Optional<RegisteredServer> optServer = server.getServer(serverName);
        if (optServer.isEmpty()) return;

        RegisteredServer registeredServer = optServer.get();
        long startTime = System.currentTimeMillis();

        registeredServer.ping().whenComplete((ping, error) -> {
            long latency = System.currentTimeMillis() - startTime;

            if (error != null || ping == null) {
                handleFallback(serverName, "offline");
                return;
            }

            if (latency > configManager.getMaxPing()) {
                handleFallback(serverName, "high ping (" + latency + "ms)");
            }
        });
    }

    private void handleFallback(String serverName, String reason) {
        long now = System.currentTimeMillis();
        Long lastTime = lastFallbackTime.get(serverName);

        if (lastTime != null && (now - lastTime) < FALLBACK_COOLDOWN_MS) {
            return;
        }

        Optional<RegisteredServer> optServer = server.getServer(serverName);
        if (optServer.isEmpty()) return;

        RegisteredServer registeredServer = optServer.get();
        var players = registeredServer.getPlayersConnected();

        if (players.isEmpty()) {
            if (lastTime == null) {
                logger.warn("Server '{}' is {} but no players online", serverName, reason);
                lastFallbackTime.put(serverName, now);
            }
            return;
        }

        logger.warn("Server '{}' is {} — redirecting {} players",
                serverName, reason, players.size());

        lastFallbackTime.put(serverName, now);

        List<String> fallbacks = configManager.getFallbackFor(serverName);
        if (fallbacks.isEmpty()) return;

        for (Player player : players) {
            sendToBestFallback(player, fallbacks);
        }
    }

    private void sendToBestFallback(Player player, List<String> fallbacks) {
        for (String fbName : fallbacks) {
            String trimmed = fbName.trim();
            Optional<RegisteredServer> optFb = server.getServer(trimmed);
            if (optFb.isEmpty()) continue;

            RegisteredServer fallback = optFb.get();

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

            return;
        }
    }
}
