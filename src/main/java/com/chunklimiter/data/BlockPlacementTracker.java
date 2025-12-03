package com.chunklimiter.data;

import com.chunklimiter.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which player placed a block at each position.
 * This allows us to correctly decrement the right player's count when a block is broken.
 * 
 * Structure: "dimension_x_y_z" -> PlayerUUID
 */
public class BlockPlacementTracker {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Path DATA_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(Constants.MOD_ID)
            .resolve("placements.json");

    /** Position key -> Player UUID who placed the block */
    private final Map<String, String> placements = new ConcurrentHashMap<>();
    private boolean dirty = false;

    public BlockPlacementTracker() {
        load();
    }

    /**
     * Create a unique key for a block position.
     */
    public static String getPositionKey(ResourceKey<Level> dimension, BlockPos pos) {
        return dimension.location().toString() + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    /**
     * Record that a player placed a block at a position.
     */
    public void recordPlacement(ResourceKey<Level> dimension, BlockPos pos, UUID playerId) {
        String key = getPositionKey(dimension, pos);
        placements.put(key, playerId.toString());
        dirty = true;
    }

    /**
     * Get who placed a block at a position.
     * @return Player UUID or null if not tracked
     */
    public UUID getPlacedBy(ResourceKey<Level> dimension, BlockPos pos) {
        String key = getPositionKey(dimension, pos);
        String uuidStr = placements.get(key);
        if (uuidStr == null) return null;
        try {
            return UUID.fromString(uuidStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Remove placement record when a block is broken.
     * @return the UUID of the player who placed it, or null
     */
    public UUID removePlacement(ResourceKey<Level> dimension, BlockPos pos) {
        String key = getPositionKey(dimension, pos);
        String uuidStr = placements.remove(key);
        if (uuidStr != null) {
            dirty = true;
            try {
                return UUID.fromString(uuidStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Load placements from disk.
     */
    public void load() {
        if (Files.notExists(DATA_FILE)) return;

        try (Reader reader = Files.newBufferedReader(DATA_FILE)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                placements.clear();
                placements.putAll(loaded);
                Constants.LOGGER.info("Loaded {} block placements", placements.size());
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to load block placements", e);
        }
    }

    /**
     * Save placements to disk if dirty.
     */
    public void saveIfDirty() {
        if (!dirty) return;
        save();
    }

    /**
     * Save placements to disk.
     */
    public void save() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(DATA_FILE)) {
                GSON.toJson(placements, writer);
                dirty = false;
            }
        } catch (IOException e) {
            Constants.LOGGER.error("Failed to save block placements", e);
        }
    }

    /**
     * Get total tracked placements.
     */
    public int getTrackedCount() {
        return placements.size();
    }

    /**
     * Clear all data.
     */
    public void clear() {
        placements.clear();
        dirty = true;
    }
}