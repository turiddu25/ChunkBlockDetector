package com.chunklimiter;

import com.chunklimiter.commands.ChunkLimitCommand;
import com.chunklimiter.data.BlockPlacementTracker;
import com.chunklimiter.data.ChunkBlockStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.kyori.adventure.platform.fabric.FabricServerAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkBlockLimiter implements ModInitializer {
    private static ConfigManager config;
    private static ChunkBlockStorage storage;
    private static BlockPlacementTracker tracker;
    private static FabricServerAudiences adventure;
    private static MinecraftServer server;
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    
    // Cache for chunk block counts: "dimension_chunkX_chunkZ_blockId" -> count (per block type)
    private static final Map<String, CachedCount> countCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 30_000; // 30s cache to avoid repeated scans
    private static final Set<String> seededCounts = ConcurrentHashMap.newKeySet();
    
    private int tickCounter = 0;
    private static final int SAVE_INTERVAL_TICKS = 6000; // 5 minutes

    private static class CachedCount {
        final int count;
        final long timestamp;
        
        CachedCount(int count) {
            this.count = count;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_DURATION_MS;
        }
    }
    
    private static String seedKey(ServerLevel level, ChunkPos chunkPos, String blockId) {
        return level.dimension().location() + "_" + chunkPos.x + "_" + chunkPos.z + "_" + blockId;
    }

    @Override
    public void onInitialize() {
        Constants.LOGGER.info("Initializing {}...", Constants.MOD_NAME);

        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            server = srv;
            adventure = FabricServerAudiences.of(srv);
            
            config = new ConfigManager();
            config.load();
            
            storage = new ChunkBlockStorage();
            tracker = new BlockPlacementTracker();
            
            Constants.LOGGER.info("{} loaded with {} block limits configured", 
                Constants.MOD_NAME, config.get().blockLimits.size());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            Constants.LOGGER.info("Saving {} data...", Constants.MOD_NAME);
            if (storage != null) storage.saveAll();
            if (tracker != null) tracker.save();
            if (adventure != null) {
                adventure.close();
                adventure = null;
            }
            countCache.clear();
            seededCounts.clear();
            server = null;
        });

        // Periodic save and cache cleanup
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            tickCounter++;
            if (tickCounter >= SAVE_INTERVAL_TICKS) {
                tickCounter = 0;
                if (storage != null) storage.saveDirty();
                if (tracker != null) tracker.saveIfDirty();
                // Clean expired cache entries
                countCache.entrySet().removeIf(e -> e.getValue().isExpired());
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ChunkLimitCommand.register(dispatcher);
        });

        Constants.LOGGER.info("{} initialized!", Constants.MOD_NAME);
    }

    /**
     * Check if a block type has a configured limit.
     */
    public static boolean hasLimit(String blockId) {
        return config != null && config.hasLimit(blockId);
    }

    /**
     * Check if a block placement should be blocked.
     * This uses ACTUAL block counting in the chunk - completely foolproof.
     * 
     * @return true if placement should be BLOCKED, false if allowed
     */
    public static boolean shouldBlockPlacement(ServerPlayer player, ServerLevel level, 
                                               BlockPos pos, String blockId) {
        if (config == null || !config.get().enabled) {
            return false;
        }

        // Check for admin bypass
        if (player.hasPermissions(config.get().bypassPermissionLevel)) {
            return false;
        }

        // Check if this block has a limit
        int limit = config.getLimit(blockId);
        if (limit < 0) {
            return false; // No limit configured
        }

        // If storage is unavailable, fall back to a one-off scan
        if (storage == null) {
            int worldCount = countBlocksInChunk(level, pos, blockId);
            if (worldCount >= limit) {
                sendLimitReached(player, blockId, limit, worldCount);
                return true;
            }
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(pos);
        String seedKey = seedKey(level, chunkPos, blockId);
        int trackedCount = storage.getTotalCount(level.dimension(), pos, blockId);

        // One-time seed from world if this chunk/block hasn't been initialised and is currently empty in counters
        if (trackedCount == 0 && !seededCounts.contains(seedKey)) {
            int actualCount = countBlocksInChunk(level, pos, blockId);
            if (actualCount > 0) {
                storage.seedBlockCount(level.dimension(), chunkPos, blockId, actualCount);
                trackedCount = actualCount;
            }
            seededCounts.add(seedKey);
        }

        int nextCount = trackedCount + 1;

        if (nextCount > limit) {
            sendLimitReached(player, blockId, limit, trackedCount);
            return true;
        }

        // Optional warning using counters only
        double warnThreshold = config.get().warningThreshold;
        if (config.get().showWarnings
                && warnThreshold > 0.0
                && nextCount >= limit * warnThreshold
                && nextCount < limit) {
            sendLimitWarning(player, blockId, limit, nextCount);
        }

        return false; // ALLOW the placement
    }

    /**
     * Count blocks of a specific type in a chunk.
     * This scans the actual chunk - completely accurate.
     */
    public static int countBlocksInChunk(ServerLevel level, BlockPos pos, String blockId) {
        ChunkPos chunkPos = new ChunkPos(pos);
        String cacheKey = level.dimension().location() + "_" + chunkPos.x + "_" + chunkPos.z + "_" + blockId;
        
        // Check cache first
        CachedCount cached = countCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.count;
        }
        
        // Get the block to search for
        ResourceLocation blockLoc = ResourceLocation.tryParse(blockId);
        if (blockLoc == null) {
            return 0;
        }
        
        Block targetBlock = BuiltInRegistries.BLOCK.get(blockLoc);
        if (targetBlock == null) {
            return 0;
        }
        
        // Get the chunk
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        
        // Count blocks
        int count = 0;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int startX = chunkPos.getMinBlockX();
        int startZ = chunkPos.getMinBlockZ();
        
        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    checkPos.set(startX + x, y, startZ + z);
                    BlockState state = chunk.getBlockState(checkPos);
                    if (state.is(targetBlock)) {
                        count++;
                    }
                }
            }
        }
        
        // Cache the result
        countCache.put(cacheKey, new CachedCount(count));
        
        if (config != null && config.get().debug) {
            Constants.LOGGER.info("[DEBUG] Counted {} {} in chunk [{}, {}]", 
                count, blockId, chunkPos.x, chunkPos.z);
        }
        
        return count;
    }

    /**
     * Invalidate cache for a chunk when blocks change for a specific block type.
     */
    public static void invalidateChunkCache(ServerLevel level, BlockPos pos, String blockId) {
        ChunkPos chunkPos = new ChunkPos(pos);
        String cacheKey = level.dimension().location() + "_" + chunkPos.x + "_" + chunkPos.z + "_" + blockId;
        countCache.remove(cacheKey);
    }

    /**
     * Called AFTER a block is successfully placed.
     * Records placement for per-player tracking (admin visibility).
     * Also invalidates cache.
     */
    public static void onBlockPlaced(ServerPlayer player, BlockPos pos, String blockId) {
        if (config == null || storage == null || tracker == null) return;
        if (!config.get().enabled) return;
        
        // Only track blocks that have limits
        if (!config.hasLimit(blockId)) return;

        // Invalidate cache for this chunk
        if (player.level() instanceof ServerLevel serverLevel) {
            invalidateChunkCache(serverLevel, pos, blockId);
        }

        // Skip per-player tracking if player has bypass
        if (player.hasPermissions(config.get().bypassPermissionLevel)) return;

        // Record for per-player tracking (admin visibility)
        storage.incrementBlock(player.level().dimension(), pos, player.getUUID(), blockId);
        tracker.recordPlacement(player.level().dimension(), pos, player.getUUID());

        if (config.get().debug) {
            int chunkCount = storage.getTotalCount(player.level().dimension(), pos, blockId);
            Constants.LOGGER.info("[DEBUG] Block placed: {} at {} by {} (chunk total: {})",
                blockId, pos, player.getScoreboardName(), chunkCount);
        }
    }

    /**
     * Handle block breaking - for per-player tracking and cache invalidation.
     */
    public static void onBlockBroken(ServerPlayer breaker, BlockPos pos, BlockState blockState) {
        if (config == null || storage == null || tracker == null) return;
        if (!config.get().enabled) return;

        String blockId = blockState.getBlock().builtInRegistryHolder().key().location().toString();
        
        // Only track blocks that have limits
        if (!config.hasLimit(blockId)) return;

        // Invalidate cache for this chunk
        if (breaker.level() instanceof ServerLevel serverLevel) {
            invalidateChunkCache(serverLevel, pos, blockId);
        }

        // Find who placed this block and update per-player tracking
        java.util.UUID placerId = tracker.removePlacement(breaker.level().dimension(), pos);
        
        if (placerId != null) {
            storage.decrementBlock(breaker.level().dimension(), pos, placerId, blockId);
        } else {
            // Unknown placer (likely seeded/pre-mod); decrement system bucket
            storage.decrementSystemBlock(breaker.level().dimension(), pos, blockId);
        }
        
        if (config.get().debug) {
            Constants.LOGGER.info("[DEBUG] Block broken: {} at {} by {} (placer: {})", 
                blockId, pos, breaker.getScoreboardName(), placerId);
        }
    }

    private static void sendLimitReached(ServerPlayer player, String blockId, int limit, int current) {
        if (!config.get().sendLimitMessage || adventure == null) return;
        String message = config.get().limitReachedMessage;
        Component parsed = MINI.deserialize(message,
            Placeholder.unparsed("block", formatBlockName(blockId)),
            Placeholder.unparsed("limit", String.valueOf(limit)),
            Placeholder.unparsed("current", String.valueOf(current))
        );
        adventure.player(player.getUUID()).sendMessage(parsed);
    }

    private static void sendLimitWarning(ServerPlayer player, String blockId, int limit, int current) {
        if (adventure == null) return;
        String message = config.get().limitWarningMessage;
        Component parsed = MINI.deserialize(message,
            Placeholder.unparsed("block", formatBlockName(blockId)),
            Placeholder.unparsed("limit", String.valueOf(limit)),
            Placeholder.unparsed("current", String.valueOf(current))
        );
        adventure.player(player.getUUID()).sendMessage(parsed);
    }

    private static String formatBlockName(String blockId) {
        String name = blockId;
        if (name.contains(":")) {
            name = name.substring(name.indexOf(":") + 1);
        }
        return name.replace("_", " ");
    }

    // Getters for other classes
    public static ConfigManager getConfig() {
        return config;
    }

    public static ChunkBlockStorage getStorage() {
        return storage;
    }

    public static BlockPlacementTracker getTracker() {
        return tracker;
    }

    public static FabricServerAudiences adventure() {
        return adventure;
    }

    public static MinecraftServer getServer() {
        return server;
    }
}
