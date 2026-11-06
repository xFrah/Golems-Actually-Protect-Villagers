package com.fdimo.golemsactuallyprotectvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/golems_actually_protect_villagers.json");

    public static Config DATA = new Config();

    // Radius around a villager to consider an enemy as an acute danger to them
    public double villagerProximityThreatRadius = 10.0;

    // Radius around golems to scan for threats and villagers
    public double golemScanRadius = 32.0;

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
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                DATA = GSON.fromJson(reader, Config.class);
                if (DATA == null) {
                    DATA = new Config();
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
        CONFIG_FILE.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(DATA, writer);
        } catch (IOException e) {
            Constants.LOG.error("Failed to save config file", e);
        }
    }
}
