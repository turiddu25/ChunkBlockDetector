package com.chunklimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(Constants.MOD_ID)
            .resolve("config.json");

    private ConfigData data = new ConfigData();

    public void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.notExists(CONFIG_PATH)) {
                // Create default config with example limits
                data = new ConfigData();
                save();
                Constants.LOGGER.info("Created default config at {}", CONFIG_PATH);
                return;
            }

            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                if (loaded != null) {
                    data = loaded;
                    Constants.LOGGER.info("Loaded {} block limits from config", data.blockLimits.size());
                }
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Failed to load config, using defaults", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Failed to save config", e);
        }
    }

    public ConfigData get() {
        return data;
    }

    /**
     * Get the limit for a specific block. Returns -1 if no limit (unlimited).
     */
    public int getLimit(String blockId) {
        return data.blockLimits.getOrDefault(blockId, -1);
    }

    /**
     * Check if a block has a limit configured.
     */
    public boolean hasLimit(String blockId) {
        return data.blockLimits.containsKey(blockId);
    }

    public static class ConfigData {
        /** Whether the limiter is enabled */
        public boolean enabled = true;

        /** 
         * Block limits - key is block ID (e.g., "minecraft:hopper"), value is max per chunk per player.
         * Use -1 for unlimited.
         */
        public Map<String, Integer> blockLimits = new HashMap<>();

        /** Permission level required to bypass limits (default 2 = OP) */
        public int bypassPermissionLevel = 2;

        /** Whether to send a message when a player hits a limit */
        public boolean sendLimitMessage = true;

        /** Message sent when limit is reached. Placeholders: %block%, %limit%, %current% */
        public String limitReachedMessage = "<red>You can only place <yellow>%limit%</yellow> <gold>%block%</gold> per chunk! (Currently: %current%)</red>";

        /** Message sent as warning when approaching limit. Placeholders: %block%, %limit%, %current% */
        public String limitWarningMessage = "<yellow>Warning: You have placed <gold>%current%/%limit%</gold> %block% in this chunk.</yellow>";

        /** Percentage of limit to show warning (0.0 to 1.0, e.g., 0.8 = 80%) */
        public double warningThreshold = 0.8;

        /** Whether to show warning messages */
        public boolean showWarnings = true;

        public ConfigData() {
            // Default example limits
            blockLimits.put("minecraft:hopper", 16);
            blockLimits.put("minecraft:chest", 54);
            blockLimits.put("minecraft:trapped_chest", 27);
            blockLimits.put("minecraft:barrel", 54);
            blockLimits.put("minecraft:dispenser", 16);
            blockLimits.put("minecraft:dropper", 16);
            blockLimits.put("minecraft:piston", 32);
            blockLimits.put("minecraft:sticky_piston", 32);
            blockLimits.put("minecraft:observer", 32);
            blockLimits.put("minecraft:comparator", 32);
            blockLimits.put("minecraft:repeater", 64);
            blockLimits.put("minecraft:redstone_lamp", 32);
            // Cobblemon blocks
            blockLimits.put("cobblemon:healing_machine", 2);
            blockLimits.put("cobblemon:pc", 4);
        }
    }
}