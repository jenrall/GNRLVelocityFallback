package ir.gnrl.fallback;

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

    /** قفل اسپم: serverName -> آخرین زمانی که fallback انجام شد (ms) */
    private final Map<String, Long> lastFallbackTime = new HashMap<>();

    /** بعد از هر fallback، چند ثانیه پیام نده */
    private static final long FALLBACK_COOLDOWN_MS = 30_000; // ۳۰ ثانیه

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
            return;
        }

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

    /* ---------------- Fallback با cooldown ---------------- */

    private void handleFallback(String serverName, String reason) {
        long now = System.currentTimeMillis();
        Long lastTime = lastFallbackTime.get(serverName);

        // اگه توی cooldown هستیم، هیچ کاری نکن
        if (lastTime != null && (now - lastTime) < FALLBACK_COOLDOWN_MS) {
            return;
        }

        // چک کن اگه کسی توی این سرور هست
        Optional<RegisteredServer> optServer = server.getServer(serverName);
        if (optServer.isEmpty()) return;

        RegisteredServer registeredServer = optServer.get();
        var players = registeredServer.getPlayersConnected();

        // اگه هیچ بازیکنی نیست، فقط یه بار لاگ کن و ادامه نده
        if (players.isEmpty()) {
            // فقط اگه توی cooldown نیستیم، یه لاگ بندازیم
            if (lastTime == null) {
                logger.warn("Server '{}' is {} but no players online", serverName, reason);
                lastFallbackTime.put(serverName, now);
            }
            return;
        }

        // لاگ
        logger.warn("Server '{}' is {} — redirecting {} players",
                serverName, reason, players.size());

        // قفل رو بذار
        lastFallbackTime.put(serverName, now);

        // انتقال بازیکنان
        List<String> fallbacks = configManager.getFallbackFor(serverName);
        if (fallbacks.isEmpty()) {
            logger.warn("No fallback configured for server '{}'", serverName);
            return;
        }

        for (Player player : players) {
            sendToBestFallback(player, fallbacks);
        }
    }

    /* ---------------- Redirect logic ---------------- */

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
