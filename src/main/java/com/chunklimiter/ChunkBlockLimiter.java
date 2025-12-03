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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public class ChunkBlockLimiter implements ModInitializer {
    private static ConfigManager config;
    private static ChunkBlockStorage storage;
    private static BlockPlacementTracker tracker;
    private static FabricServerAudiences adventure;
    private static MinecraftServer server;
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    
    private int tickCounter = 0;
    private static final int SAVE_INTERVAL_TICKS = 6000; // 5 minutes

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
            server = null;
        });

        // Periodic save
        ServerTickEvents.END_SERVER_TICK.register(srv -> {
            tickCounter++;
            if (tickCounter >= SAVE_INTERVAL_TICKS) {
                tickCounter = 0;
                if (storage != null) storage.saveDirty();
                if (tracker != null) tracker.saveIfDirty();
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ChunkLimitCommand.register(dispatcher);
        });

        Constants.LOGGER.info("{} initialized!", Constants.MOD_NAME);
    }

    /**
     * Check if a block placement should be blocked.
     * Called from the mixin BEFORE the block is placed.
     * 
     * @return true if placement should be BLOCKED, false if allowed
     */
    public static boolean shouldBlockPlacement(ServerPlayer player, net.minecraft.world.level.Level world, 
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

        // Get current count for this player in this chunk
        int currentCount = storage.getCount(
            world.dimension(), 
            pos, 
            player.getUUID(), 
            blockId
        );

        // Check if at or over limit
        if (currentCount >= limit) {
            // Send limit reached message
            if (config.get().sendLimitMessage && adventure != null) {
                String message = config.get().limitReachedMessage;
                Component parsed = MINI.deserialize(message,
                    Placeholder.unparsed("block", formatBlockName(blockId)),
                    Placeholder.unparsed("limit", String.valueOf(limit)),
                    Placeholder.unparsed("current", String.valueOf(currentCount))
                );
                adventure.player(player.getUUID()).sendMessage(parsed);
            }
            return true; // BLOCK the placement
        }

        // Check for warning threshold
        if (config.get().showWarnings && currentCount >= limit * config.get().warningThreshold) {
            if (adventure != null) {
                String message = config.get().limitWarningMessage;
                Component parsed = MINI.deserialize(message,
                    Placeholder.unparsed("block", formatBlockName(blockId)),
                    Placeholder.unparsed("limit", String.valueOf(limit)),
                    Placeholder.unparsed("current", String.valueOf(currentCount + 1)) // +1 because this placement will succeed
                );
                adventure.player(player.getUUID()).sendMessage(parsed);
            }
        }

        // Allow placement and track it
        storage.incrementBlock(world.dimension(), pos, player.getUUID(), blockId);
        tracker.recordPlacement(world.dimension(), pos, player.getUUID());
        
        return false; // ALLOW the placement
    }

    /**
     * Handle block breaking - decrement the placer's count.
     * Called from the mixin BEFORE the block is actually removed.
     */
    public static void onBlockBroken(ServerPlayer breaker, BlockPos pos, BlockState blockState) {
        if (config == null || storage == null || tracker == null) return;
        if (!config.get().enabled) return;

        String blockId = blockState.getBlock().builtInRegistryHolder().key().location().toString();
        
        // Only track blocks that have limits
        if (!config.hasLimit(blockId)) return;

        // Find who placed this block
        java.util.UUID placerId = tracker.removePlacement(breaker.level().dimension(), pos);
        
        if (placerId != null) {
            // Decrement the placer's count (not the breaker's)
            storage.decrementBlock(breaker.level().dimension(), pos, placerId, blockId);
        }
        // If we don't know who placed it (e.g., placed before mod was installed), 
        // we can't decrement anyone's count - this is the safest approach
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