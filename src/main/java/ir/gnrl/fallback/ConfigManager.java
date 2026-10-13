package ir.gnrl.fallback;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ConfigManager {

    private final VelocityFallback plugin;
    private final Path dataDirectory;
    private final Logger logger;

    private final Map<String, List<String>> fallbackChains = new LinkedHashMap<>();
    private long maxPing = 500;
    private int checkInterval = 10;

    public ConfigManager(VelocityFallback plugin, Path dataDirectory) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.logger = plugin.getLogger();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configFile = dataDirectory.resolve("config.yml");

            // اگه کانفیگ نیست، از resources کپی کن
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                        logger.info("Created default config at {}", configFile);
                    }
                }
            }

            // خوندن YAML
            Yaml yaml = new Yaml();
            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(configFile)) {
                root = yaml.load(in);
            }

            if (root == null) {
                logger.warn("Config is empty");
                return;
            }

            // max-ping
            Object maxPingObj = root.get("max-ping");
            if (maxPingObj instanceof Number) {
                maxPing = ((Number) maxPingObj).longValue();
            }

            // check-interval
            Object intervalObj = root.get("check-interval");
            if (intervalObj instanceof Number) {
                checkInterval = ((Number) intervalObj).intValue();
            }

            // fallback chains
            fallbackChains.clear();
            Object fallbackObj = root.get("fallback");
            if (fallbackObj instanceof Map) {
                Map<String, Object> fallbackMap = (Map<String, Object>) fallbackObj;
                for (Map.Entry<String, Object> entry : fallbackMap.entrySet()) {
                    String serverName = entry.getKey();
                    Object value = entry.getValue();
                    if (value instanceof List) {
                        List<String> targets = new ArrayList<>();
                        for (Object o : (List<?>) value) {
                            targets.add(String.valueOf(o).trim());
                        }
                        fallbackChains.put(serverName, targets);
                    }
                }
            }

            logger.info("Loaded {} fallback chains. Max ping: {}ms, Check interval: {}s",
                    fallbackChains.size(), maxPing, checkInterval);

        } catch (IOException e) {
            logger.error("Failed to load config", e);
        } catch (Exception e) {
            logger.error("Failed to parse config", e);
        }
    }

    public Map<String, List<String>> getFallbackChains() {
        return fallbackChains;
    }

    public List<String> getFallbackFor(String serverName) {
        return fallbackChains.getOrDefault(serverName, Collections.emptyList());
    }

    public long getMaxPing() {
        return maxPing;
    }

    public int getCheckInterval() {
        return checkInterval;
    }
}
