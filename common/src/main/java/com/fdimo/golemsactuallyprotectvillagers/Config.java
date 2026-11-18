package com.fdimo.golemsactuallyprotectvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

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

    // Maximum nodes explored during async A* pathfinding
    public int maxPathfindingNodes = 1000;

    // Capacity of the background thread pool queue (bounded queue with DiscardOldestPolicy)
    public int asyncQueueCapacity = 1000;

    // Print real-time queue metrics in chat / logs
    public boolean enableQueueDebug = false;

    // Broadcast golem AI state and actions directly to in-game Minecraft chat
    public boolean enableChatDebug = false;

    // Enable debug logging
    public boolean enableDebugLogs = false;

    // Show red particles on unrecoverable/dangerous ground (pits, deep water, cliffs) where golems cannot safely go
    public boolean showPitFallDebug = false;

    // Show blue particles along the active path the golem is walking on
    public boolean showPathDebug = false;

    // Maximum vertical fall distance allowed when pathfinding to threats (default: 32 blocks)
    public int maxFallDistance = 32;

    // Movement speed modifier applied to iron golems when pursuing hostile mobs (e.g. 0.20 for +20% speed)
    public double mobPursuitSpeedModifier = 0.20;

    // Enable event-driven villager distress beacon system (villagers broadcast SOS to nearby golems)
    public boolean enableVillagerDistressSignals = true;

    // Interval in ticks between distress broadcasts per villager while panicking (40 ticks = 2 seconds)
    public int villagerDistressBroadcastIntervalTicks = 40;

    // Enable iron golems climbing/mantling up 2-block ledges and obstacles when pursuing threats or rescuing villagers
    public boolean enableLedgeClimb = true;

    // Maximum height in blocks an iron golem can climb/mantle (default: 2.0 blocks)
    public double maxClimbHeight = 2.0;

    // Speed multiplier for iron golem ledge climbing/mantling animation (default: 0.35F, lower is slower, higher is faster)
    public float ledgeClimbSpeed = 0.35F;

    // Cost penalty (malus) added to path nodes that require climbing up ledges, fences, or walls (default: 5.0F).
    // Discourages climbing when flat ground or open gates exist, but still allows climbing when necessary.
    public float climbMalus = 5.0F;

    // Cost penalty (malus) added to path nodes immediately adjacent to fences or walls (default: 2.5F).
    // Steers the 1.4-block-wide iron golem away from fence lines to prevent snagging its hitbox when open space exists.
    public float fenceProximityMalus = 2.5F;

    // Enable glowing red eyes on iron golems when pursuing threats or protecting villagers
    public boolean enableRedPursuitEyes = true;

    public static void load() {
        Path configPath = CONFIG_FILE.toPath();
        boolean needsSave = false;

        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) {
                    DATA = GSON.fromJson(json, Config.class);
                    if (DATA == null) {
                        DATA = new Config();
                        needsSave = true;
                    }

                    // Handle legacy config upgrade
                    if (!json.has("maxFallDistance") && json.has("maxHeroicFallDistance")) {
                        try {
                            DATA.maxFallDistance = json.get("maxHeroicFallDistance").getAsInt();
                        } catch (Exception ignored) {
                        }
                    }

                    // Detect missing keys (e.g. upgrading from an older version of the mod)
                    if (!json.has("villagerProximityThreatRadius")
                        || !json.has("golemScanRadius")
                        || !json.has("maxPathfindingNodes")
                        || !json.has("asyncQueueCapacity")
                        || !json.has("enableQueueDebug")
                        || !json.has("enableChatDebug")
                        || !json.has("enableDebugLogs")
                        || !json.has("showPitFallDebug")
                        || !json.has("showPathDebug")
                        || !json.has("maxFallDistance")
                        || !json.has("mobPursuitSpeedModifier")
                        || !json.has("enableVillagerDistressSignals")
                        || !json.has("villagerDistressBroadcastIntervalTicks")
                        || !json.has("enableLedgeClimb")
                        || !json.has("maxClimbHeight")
                        || !json.has("ledgeClimbSpeed")
                        || !json.has("climbMalus")
                        || !json.has("fenceProximityMalus")
                        || !json.has("enableRedPursuitEyes")) {
                        needsSave = true;
                    }
                } else {
                    DATA = new Config();
                    needsSave = true;
                }

                if (DATA.villagerDistressBroadcastIntervalTicks <= 0) {
                    DATA.villagerDistressBroadcastIntervalTicks = 40;
                    needsSave = true;
                }

                if (DATA.golemScanRadius <= 0) {
                    DATA.golemScanRadius = 48.0;
                    needsSave = true;
                }
                if (DATA.villagerProximityThreatRadius <= 0) {
                    DATA.villagerProximityThreatRadius = 10.0;
                    needsSave = true;
                }
                if (DATA.maxPathfindingNodes <= 0) {
                    DATA.maxPathfindingNodes = 1000;
                    needsSave = true;
                }
                if (DATA.asyncQueueCapacity <= 0) {
                    DATA.asyncQueueCapacity = 1000;
                    needsSave = true;
                }
                if (DATA.mobPursuitSpeedModifier < 0.0) {
                    DATA.mobPursuitSpeedModifier = 0.0;
                    needsSave = true;
                }
                if (DATA.maxFallDistance <= 0) {
                    DATA.maxFallDistance = 32;
                    needsSave = true;
                }
                if (DATA.ledgeClimbSpeed <= 0.0F) {
                    DATA.ledgeClimbSpeed = 0.35F;
                    needsSave = true;
                }
                if (DATA.climbMalus < 0.0F) {
                    DATA.climbMalus = 5.0F;
                    needsSave = true;
                }
            } catch (Exception e) {
                Constants.LOG.error("Failed to read config file", e);
                DATA = new Config();
                needsSave = true;
            }
        } else {
            needsSave = true;
        }

        if (needsSave) {
            save();
        }
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
