package com.chunklimiter.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks block counts for a single chunk.
 * Structure: PlayerUUID -> BlockID -> Count
 */
public class ChunkBlockData {
    /** Dimension + Chunk position identifier (e.g., "minecraft:overworld_0_0") */
    public String chunkKey;
    
    /** Player UUID -> Block ID -> Count */
    public Map<String, Map<String, Integer>> playerBlocks = new HashMap<>();

    public ChunkBlockData() {}

    public ChunkBlockData(String chunkKey) {
        this.chunkKey = chunkKey;
    }

    /**
     * Get the count of a specific block type for a player in this chunk.
     */
    public int getCount(UUID playerId, String blockId) {
        Map<String, Integer> playerData = playerBlocks.get(playerId.toString());
        if (playerData == null) return 0;
        return playerData.getOrDefault(blockId, 0);
    }

    /**
     * Increment the count of a block for a player.
     * @return the new count
     */
    public int increment(UUID playerId, String blockId) {
        Map<String, Integer> playerData = playerBlocks.computeIfAbsent(
            playerId.toString(), 
            k -> new HashMap<>()
        );
        int newCount = playerData.getOrDefault(blockId, 0) + 1;
        playerData.put(blockId, newCount);
        return newCount;
    }

    /**
     * Add an amount to a player's count (used for system seeding).
     */
    public void addToPlayer(UUID playerId, String blockId, int amount) {
        if (amount <= 0) return;
        Map<String, Integer> playerData = playerBlocks.computeIfAbsent(
            playerId.toString(),
            k -> new HashMap<>()
        );
        playerData.merge(blockId, amount, Integer::sum);
    }

    /**
     * Decrement the count of a block for a player.
     * @return the new count (minimum 0)
     */
    public int decrement(UUID playerId, String blockId) {
        Map<String, Integer> playerData = playerBlocks.get(playerId.toString());
        if (playerData == null) return 0;
        
        int current = playerData.getOrDefault(blockId, 0);
        int newCount = Math.max(0, current - 1);
        
        if (newCount == 0) {
            playerData.remove(blockId);
            // Clean up empty player entry
            if (playerData.isEmpty()) {
                playerBlocks.remove(playerId.toString());
            }
        } else {
            playerData.put(blockId, newCount);
        }
        
        return newCount;
    }

    /**
     * Get all block counts for a player in this chunk.
     */
    public Map<String, Integer> getPlayerBlocks(UUID playerId) {
        return playerBlocks.getOrDefault(playerId.toString(), new HashMap<>());
    }

    /**
     * Get total count of a specific block type across ALL players in this chunk.
     */
    public int getTotalCount(String blockId) {
        int total = 0;
        for (Map<String, Integer> playerData : playerBlocks.values()) {
            total += playerData.getOrDefault(blockId, 0);
        }
        return total;
    }

    /**
     * Check if this chunk data is empty (no tracked blocks).
     */
    public boolean isEmpty() {
        return playerBlocks.isEmpty();
    }

    /**
     * Clear all data for a specific player.
     */
    public void clearPlayer(UUID playerId) {
        playerBlocks.remove(playerId.toString());
    }
}
