package com.fdimo.golemsactuallyprotectvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/golems_actually_protect_villagers.json");

    public static Config DATA = new Config();

    // Radius around a villager to consider an enemy as an acute danger to them
    public double villagerProximityThreatRadius = 10.0;

    // Radius around golems to scan for threats and villagers
    public double golemScanRadius = 48.0;

    // Interval in ticks between threat scans when a golem is idle (100 ticks = 5 seconds)
    public int idleScanIntervalTicks = 100;

    // Maximum number of candidates evaluated per golem in a single async evaluation
    public int singleGolemQueueLimit = 5;

    // Maximum nodes explored during async A* pathfinding
    public int maxPathfindingNodes = 200;

    // Capacity of the background thread pool queue (bounded queue with DiscardOldestPolicy)
    public int asyncQueueCapacity = 1000;

    // Print real-time queue metrics in chat / logs
    public boolean enableQueueDebug = false;

    // Broadcast golem AI state and actions directly to in-game Minecraft chat
    public boolean enableChatDebug = false;

    // Enable debug logging
    public boolean enableDebugLogs = false;

    public static void load() {
        Path configPath = CONFIG_FILE.toPath();
        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                DATA = GSON.fromJson(reader, Config.class);
                if (DATA == null) {
                    DATA = new Config();
                }
                if (DATA.idleScanIntervalTicks <= 0) {
                    DATA.idleScanIntervalTicks = 100;
                }
                if (DATA.golemScanRadius <= 0) {
                    DATA.golemScanRadius = 48.0;
                }
                if (DATA.villagerProximityThreatRadius <= 0) {
                    DATA.villagerProximityThreatRadius = 10.0;
                }
                if (DATA.singleGolemQueueLimit <= 0) {
                    DATA.singleGolemQueueLimit = 5;
                }
                if (DATA.maxPathfindingNodes <= 0) {
                    DATA.maxPathfindingNodes = 200;
                }
                if (DATA.asyncQueueCapacity <= 0) {
                    DATA.asyncQueueCapacity = 1000;
                }
            } catch (Exception e) {
                Constants.LOG.error("Failed to read config file", e);
            }
        }
        save();
        Constants.initThreadPool();
    }

    public static void save() {
        Path configPath = CONFIG_FILE.toPath();
        Path tempPath = configPath.resolveSibling(configPath.getFileName() + ".tmp");

        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }

            try (BufferedWriter writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                GSON.toJson(DATA, writer);
            }

            try {
                Files.move(tempPath, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Constants.LOG.error("Failed to save config file atomically", e);
        }
    }
}
