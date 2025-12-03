package com.chunklimiter.data;

import com.chunklimiter.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent storage of chunk block data.
 */
public class ChunkBlockStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_DIR = FabricLoader.getInstance()
            .getConfigDir()
            .resolve(Constants.MOD_ID)
            .resolve("data");

    /** In-memory cache: ChunkKey -> ChunkBlockData */
    private final Map<String, ChunkBlockData> cache = new ConcurrentHashMap<>();

    /** Dirty chunks that need saving */
    private final Map<String, Boolean> dirty = new ConcurrentHashMap<>();

    public ChunkBlockStorage() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            Constants.LOGGER.error("Failed to create data directory", e);
        }
    }

    /**
     * Create a unique key for a chunk based on dimension and position.
     */
    public static String getChunkKey(ResourceKey<Level> dimension, ChunkPos pos) {
        return dimension.location().toString() + "_" + pos.x + "_" + pos.z;
    }

    /**
     * Create a unique key for a chunk from a block position.
     */
    public static String getChunkKey(ResourceKey<Level> dimension, BlockPos pos) {
        return getChunkKey(dimension, new ChunkPos(pos));
    }

    /**
     * Get or load chunk data for a specific chunk.
     */
    public ChunkBlockData getOrCreate(ResourceKey<Level> dimension, ChunkPos pos) {
        String key = getChunkKey(dimension, pos);
        return cache.computeIfAbsent(key, this::load);
    }

    /**
     * Get or load chunk data for a specific block position.
     */
    public ChunkBlockData getOrCreate(ResourceKey<Level> dimension, BlockPos pos) {
        return getOrCreate(dimension, new ChunkPos(pos));
    }

    /**
     * Get the count of a block for a player at a position.
     */
    public int getCount(ResourceKey<Level> dimension, BlockPos pos, UUID playerId, String blockId) {
        ChunkBlockData data = getOrCreate(dimension, pos);
        return data.getCount(playerId, blockId);
    }

    /**
     * Increment block count when a player places a block.
     * @return the new count
     */
    public int incrementBlock(ResourceKey<Level> dimension, BlockPos pos, UUID playerId, String blockId) {
        String key = getChunkKey(dimension, pos);
        ChunkBlockData data = getOrCreate(dimension, pos);
        int newCount = data.increment(playerId, blockId);
        markDirty(key);
        return newCount;
    }

    /**
     * Decrement block count when a block is broken.
     * Note: This decrements for ANY player who broke it - we track by position separately.
     * @return the new count
     */
    public int decrementBlock(ResourceKey<Level> dimension, BlockPos pos, UUID playerId, String blockId) {
        String key = getChunkKey(dimension, pos);
        ChunkBlockData data = getOrCreate(dimension, pos);
        int newCount = data.decrement(playerId, blockId);
        markDirty(key);
        return newCount;
    }

    /**
     * Load chunk data from disk.
     */
    private ChunkBlockData load(String chunkKey) {
        Path file = DATA_DIR.resolve(sanitizeFileName(chunkKey) + ".json");
        if (Files.notExists(file)) {
            return new ChunkBlockData(chunkKey);
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            ChunkBlockData data = GSON.fromJson(reader, ChunkBlockData.class);
            if (data != null) {
                data.chunkKey = chunkKey;
                return data;
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to load chunk data for {}", chunkKey, e);
        }

        return new ChunkBlockData(chunkKey);
    }

    /**
     * Save a specific chunk's data to disk.
     */
    public void save(String chunkKey) {
        ChunkBlockData data = cache.get(chunkKey);
        if (data == null) return;

        // Don't save empty chunks
        if (data.isEmpty()) {
            Path file = DATA_DIR.resolve(sanitizeFileName(chunkKey) + ".json");
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                Constants.LOGGER.error("Failed to delete empty chunk file {}", chunkKey, e);
            }
            cache.remove(chunkKey);
            dirty.remove(chunkKey);
            return;
        }

        Path file = DATA_DIR.resolve(sanitizeFileName(chunkKey) + ".json");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(data, writer);
            dirty.remove(chunkKey);
        } catch (IOException e) {
            Constants.LOGGER.error("Failed to save chunk data for {}", chunkKey, e);
        }
    }

    /**
     * Mark a chunk as needing to be saved.
     */
    public void markDirty(String chunkKey) {
        dirty.put(chunkKey, true);
    }

    /**
     * Save all dirty chunks.
     */
    public void saveDirty() {
        for (String key : dirty.keySet()) {
            save(key);
        }
    }

    /**
     * Save all cached data.
     */
    public void saveAll() {
        for (String key : cache.keySet()) {
            save(key);
        }
        Constants.LOGGER.info("Saved {} chunk block data files", cache.size());
    }

    /**
     * Clear all cached data (use on server stop).
     */
    public void clear() {
        saveDirty();
        cache.clear();
        dirty.clear();
    }

    /**
     * Get stats for debugging.
     */
    public int getCachedChunkCount() {
        return cache.size();
    }

    public int getDirtyChunkCount() {
        return dirty.size();
    }

    /**
     * Sanitize chunk key for use as filename.
     */
    private String sanitizeFileName(String chunkKey) {
        return chunkKey.replace(":", "_").replace("/", "_");
    }
}