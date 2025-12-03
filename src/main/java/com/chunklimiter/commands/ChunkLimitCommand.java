package com.chunklimiter.commands;

import com.chunklimiter.ChunkBlockLimiter;
import com.chunklimiter.ConfigManager;
import com.chunklimiter.data.ChunkBlockData;
import com.chunklimiter.data.ChunkBlockStorage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Map;

public final class ChunkLimitCommand {
    private ChunkLimitCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chunklimit")
            .executes(ctx -> showHelp(ctx.getSource()))
            
            // /chunklimit help
            .then(Commands.literal("help")
                .executes(ctx -> showHelp(ctx.getSource())))
            
            // /chunklimit scan - Show actual block counts in current chunk
            .then(Commands.literal("scan")
                .executes(ctx -> scanCurrentChunk(ctx.getSource())))
            
            // /chunklimit check <player> - Show all chunks where player has placed limited blocks (admin)
            .then(Commands.literal("check")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> checkPlayerAllChunks(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
            
            // /chunklimit limits - Show all configured limits
            .then(Commands.literal("limits")
                .executes(ctx -> showLimits(ctx.getSource())))
            
            // /chunklimit reload - Reload config (admin)
            .then(Commands.literal("reload")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> reloadConfig(ctx.getSource())))
            
            // /chunklimit debug - Show debug info (admin)
            .then(Commands.literal("debug")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> showDebug(ctx.getSource())))
            
            // /chunklimit save - Force save data (admin)
            .then(Commands.literal("save")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> forceSave(ctx.getSource())))
            
            // /chunklimit clear <player> - Clear a player's data in current chunk (admin)
            .then(Commands.literal("clear")
                .requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> clearPlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));

        dispatcher.register(root);
        // Short alias
        dispatcher.register(Commands.literal("cl").redirect(root.build()));
    }

    private static int showHelp(CommandSourceStack source) {
        send(source, Component.text("══════ Chunk Block Limiter ══════", NamedTextColor.GOLD, TextDecoration.BOLD));
        send(source, Component.text("/chunklimit scan", NamedTextColor.YELLOW)
            .append(Component.text(" - Scan current chunk for limited blocks", NamedTextColor.GRAY)));
        send(source, Component.text("/chunklimit limits", NamedTextColor.YELLOW)
            .append(Component.text(" - Show all configured block limits", NamedTextColor.GRAY)));
        
        if (source.hasPermission(2)) {
            send(source, Component.text("/chunklimit check <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Show all chunks where player placed blocks", NamedTextColor.GRAY)));
            send(source, Component.text("/chunklimit reload", NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration", NamedTextColor.GRAY)));
            send(source, Component.text("/chunklimit clear <player>", NamedTextColor.YELLOW)
                .append(Component.text(" - Clear player's tracking data in your chunk", NamedTextColor.GRAY)));
            send(source, Component.text("/chunklimit debug", NamedTextColor.YELLOW)
                .append(Component.text(" - Show debug info", NamedTextColor.GRAY)));
            send(source, Component.text("/chunklimit save", NamedTextColor.YELLOW)
                .append(Component.text(" - Force save all data", NamedTextColor.GRAY)));
        }
        return 1;
    }

    /**
     * Scan current chunk and show actual block counts for all limited blocks.
     */
    private static int scanCurrentChunk(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Must be run by a player."));
            return 0;
        }

        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Must be run on server."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        ChunkPos chunkPos = new ChunkPos(pos);
        ConfigManager config = ChunkBlockLimiter.getConfig();

        send(source, Component.text("══════ Chunk Scan ══════", NamedTextColor.GOLD));
        send(source, Component.text("Chunk: ", NamedTextColor.GRAY)
            .append(Component.text("[" + chunkPos.x + ", " + chunkPos.z + "]", NamedTextColor.AQUA)));
        send(source, Component.text("─────────────────────", NamedTextColor.DARK_GRAY));

        boolean foundAny = false;
        for (Map.Entry<String, Integer> entry : config.get().blockLimits.entrySet()) {
            String blockId = entry.getKey();
            int limit = entry.getValue();
            
            // Count actual blocks in chunk
            int actualCount = ChunkBlockLimiter.countBlocksInChunk(serverLevel, pos, blockId);
            
            if (actualCount > 0 || limit > 0) {
                foundAny = true;
                String blockName = formatBlockName(blockId);
                
                NamedTextColor color = NamedTextColor.GREEN;
                if (limit > 0) {
                    if (actualCount >= limit) color = NamedTextColor.RED;
                    else if (actualCount >= limit * config.get().warningThreshold) color = NamedTextColor.YELLOW;
                }

                String limitStr = limit > 0 ? "/" + limit : " (no limit)";
                send(source, Component.text("  " + blockName + ": ", NamedTextColor.WHITE)
                    .append(Component.text(actualCount + limitStr, color)));
            }
        }

        if (!foundAny) {
            send(source, Component.text("  No limited blocks in this chunk.", NamedTextColor.GRAY));
        }

        return 1;
    }

    /**
     * Show all chunks where a player has placed limited blocks.
     * Each chunk is clickable to teleport there.
     */
    private static int checkPlayerAllChunks(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Player not found."));
            return 0;
        }

        Map<String, Map<String, Integer>> allData = ChunkBlockLimiter.getStorage().getAllPlayerData(player.getUUID());
        ConfigManager config = ChunkBlockLimiter.getConfig();

        send(source, Component.text("══════ Block Placements: " + player.getScoreboardName() + " ══════", NamedTextColor.GOLD));
        send(source, Component.text("Actual = live chunk scan - Tracked~ = per-player log (may drift if blocks changed unexpectedly)", NamedTextColor.GRAY));
        
        if (allData.isEmpty()) {
            send(source, Component.text("No limited blocks placed by this player.", NamedTextColor.GRAY));
            return 1;
        }

        send(source, Component.text("─────────────────────", NamedTextColor.DARK_GRAY));

        // Group by block type for summary
        Map<String, Integer> totalByBlock = new java.util.HashMap<>();
        
        for (Map.Entry<String, Map<String, Integer>> chunkEntry : allData.entrySet()) {
            String chunkKey = chunkEntry.getKey();
            Map<String, Integer> blocks = chunkEntry.getValue();
            
            // Parse chunk coordinates from key (format: "minecraft:overworld_X_Z")
            ChunkInfo chunkInfo = parseChunkKey(chunkKey);
            
            // Pre-scan actual counts for this chunk using the authoritative chunk scan
            ServerLevel level = resolveLevel(chunkInfo.dimension);
            BlockPos chunkSamplePos = new BlockPos(chunkInfo.x * 16, 64, chunkInfo.z * 16);
            Map<String, Integer> actualCounts = new java.util.HashMap<>();
            boolean hadActualCounts = false;
            if (level != null) {
                for (Map.Entry<String, Integer> blockEntry : blocks.entrySet()) {
                    String blockId = blockEntry.getKey();
                    try {
                        int actual = ChunkBlockLimiter.countBlocksInChunk(level, chunkSamplePos, blockId);
                        actualCounts.put(blockId, actual);
                        hadActualCounts = true;
                    } catch (Exception ignored) {
                        // If counting fails (e.g., chunk unload), fall back to tracked-only output.
                    }
                }
            }
            
            // Calculate teleport coordinates (center of chunk at y=64)
            int tpX = chunkInfo.x * 16 + 8;
            int tpZ = chunkInfo.z * 16 + 8;
            String tpCommand = "/tp @s " + tpX + " 100 " + tpZ;
            
            // Create clickable chunk text
            Component chunkComponent = Component.text("Chunk [" + chunkInfo.x + ", " + chunkInfo.z + "]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(tpCommand))
                .hoverEvent(HoverEvent.showText(
                    Component.text("Click to teleport\n", NamedTextColor.YELLOW)
                        .append(Component.text("Dimension: " + chunkInfo.dimension + "\n", NamedTextColor.GRAY))
                        .append(Component.text("Coords: " + tpX + ", " + tpZ, NamedTextColor.GRAY))
                ));
            // Build block info component
            Component blockComponent = Component.text(" → ", NamedTextColor.DARK_GRAY);
            for (Map.Entry<String, Integer> blockEntry : blocks.entrySet()) {
                String blockId = blockEntry.getKey();
                int trackedCount = blockEntry.getValue();
                int limit = config.getLimit(blockId);
                String blockName = formatBlockName(blockId);

                Integer actual = actualCounts.get(blockId);
                NamedTextColor actualColor = NamedTextColor.GRAY;
                if (actual != null) {
                    actualColor = NamedTextColor.AQUA;
                    if (limit > 0) {
                        if (actual >= limit) actualColor = NamedTextColor.RED;
                        else if (actual >= limit * config.get().warningThreshold) actualColor = NamedTextColor.YELLOW;
                        else actualColor = NamedTextColor.GREEN;
                    }
                }
                
                String limitStr = limit > 0 ? "/" + limit : "";
                Component perBlock = Component.text(blockName + ": ", NamedTextColor.WHITE);
                if (actual != null) {
                perBlock = perBlock.append(Component.text("actual " + actual + limitStr, actualColor));
                } else {
                    perBlock = perBlock.append(Component.text("actual n/a", NamedTextColor.GRAY));
                }

                perBlock = perBlock.append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("tracked~" + trackedCount, NamedTextColor.GOLD));

                blockComponent = blockComponent.append(perBlock)
                    .append(Component.text("  ", NamedTextColor.WHITE));
                
                // Keep approximate totals for summary
                totalByBlock.merge(blockId, trackedCount, Integer::sum);
            }

            // Add availability note if we couldn't scan actual counts
            if (!hadActualCounts) {
                blockComponent = blockComponent.append(Component.text("(chunk scan unavailable - showing tracked data only)", NamedTextColor.GRAY));
            }
            
            send(source, chunkComponent.append(blockComponent));
        }

        // Summary
        send(source, Component.text("─────────────────────", NamedTextColor.DARK_GRAY));
        send(source, Component.text("Summary (tracked~ totals):", NamedTextColor.WHITE, TextDecoration.BOLD));
        
        for (Map.Entry<String, Integer> entry : totalByBlock.entrySet()) {
            String blockName = formatBlockName(entry.getKey());
            int total = entry.getValue();
            send(source, Component.text("  " + blockName + ": ", NamedTextColor.GRAY)
                .append(Component.text(total + " total", NamedTextColor.WHITE)));
        }
        
        send(source, Component.text("  Chunks with placements: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(allData.size()), NamedTextColor.WHITE)));

        return 1;
    }

    private static int showLimits(CommandSourceStack source) {
        ConfigManager config = ChunkBlockLimiter.getConfig();
        Map<String, Integer> limits = config.get().blockLimits;

        send(source, Component.text("══════ Configured Block Limits ══════", NamedTextColor.GOLD));
        
        if (limits.isEmpty()) {
            send(source, Component.text("No block limits configured.", NamedTextColor.GRAY));
            return 1;
        }

        send(source, Component.text("Status: ", NamedTextColor.GRAY)
            .append(Component.text(config.get().enabled ? "ENABLED" : "DISABLED", 
                config.get().enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        send(source, Component.text("─────────────────────", NamedTextColor.DARK_GRAY));

        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            String blockName = formatBlockName(entry.getKey());
            int limit = entry.getValue();
            send(source, Component.text("  " + blockName + ": ", NamedTextColor.WHITE)
                .append(Component.text(limit + " per chunk", NamedTextColor.YELLOW)));
        }

        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        ChunkBlockLimiter.getConfig().load();
        send(source, Component.text("✓ ", NamedTextColor.GREEN)
            .append(Component.text("Configuration reloaded!", NamedTextColor.WHITE)));
        send(source, Component.text("  Loaded " + ChunkBlockLimiter.getConfig().get().blockLimits.size() + " block limits", NamedTextColor.GRAY));
        return 1;
    }

    private static int showDebug(CommandSourceStack source) {
        var storage = ChunkBlockLimiter.getStorage();
        var tracker = ChunkBlockLimiter.getTracker();

        send(source, Component.text("══════ Debug Info ══════", NamedTextColor.GOLD));
        send(source, Component.text("Cached chunks: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(storage.getCachedChunkCount()), NamedTextColor.WHITE)));
        send(source, Component.text("Dirty chunks: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(storage.getDirtyChunkCount()), NamedTextColor.WHITE)));
        send(source, Component.text("Tracked placements: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(tracker.getTrackedCount()), NamedTextColor.WHITE)));
        send(source, Component.text("Block limits: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(ChunkBlockLimiter.getConfig().get().blockLimits.size()), NamedTextColor.WHITE)));
        
        return 1;
    }

    private static int forceSave(CommandSourceStack source) {
        ChunkBlockLimiter.getStorage().saveAll();
        ChunkBlockLimiter.getTracker().save();
        send(source, Component.text("✓ ", NamedTextColor.GREEN)
            .append(Component.text("All data saved!", NamedTextColor.WHITE)));
        return 1;
    }

    private static int clearPlayer(CommandSourceStack source, ServerPlayer player) {
        if (player == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Player not found."));
            return 0;
        }

        // Get the chunk the command executor is standing in
        ServerPlayer executor = source.getPlayer();
        if (executor == null) {
            source.sendFailure(net.minecraft.network.chat.Component.literal("Must be run by a player."));
            return 0;
        }

        BlockPos pos = executor.blockPosition();
        ChunkPos chunkPos = new ChunkPos(pos);
        ChunkBlockData chunkData = ChunkBlockLimiter.getStorage().getOrCreate(
            executor.level().dimension(), chunkPos
        );

        chunkData.clearPlayer(player.getUUID());
        ChunkBlockLimiter.getStorage().markDirty(
            ChunkBlockStorage.getChunkKey(executor.level().dimension(), chunkPos)
        );

        send(source, Component.text("✓ ", NamedTextColor.GREEN)
            .append(Component.text("Cleared data for " + player.getScoreboardName() + " in chunk " + chunkPos.x + ", " + chunkPos.z, NamedTextColor.WHITE)));
        return 1;
    }

    /**
     * Parse chunk key format: "minecraft:overworld_X_Z" or "minecraft_overworld_X_Z"
     */
    private static ChunkInfo parseChunkKey(String chunkKey) {
        // The key format from storage is "dimension_x_z" where dimension might have underscores
        // We stored it as "dimension.toString() + "_" + pos.x + "_" + pos.z"
        // dimension.toString() gives something like "minecraft:overworld"
        
        String[] parts = chunkKey.split("_");
        if (parts.length >= 3) {
            try {
                // Last two parts are coordinates
                int z = Integer.parseInt(parts[parts.length - 1]);
                int x = Integer.parseInt(parts[parts.length - 2]);
                
                // Everything else is the dimension
                StringBuilder dim = new StringBuilder();
                for (int i = 0; i < parts.length - 2; i++) {
                    if (i > 0) dim.append("_");
                    dim.append(parts[i]);
                }
                
                return new ChunkInfo(dim.toString(), x, z);
            } catch (NumberFormatException e) {
                // Fallback
            }
        }
        return new ChunkInfo("unknown", 0, 0);
    }

    private static class ChunkInfo {
        final String dimension;
        final int x;
        final int z;
        
        ChunkInfo(String dimension, int x, int z) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
        }
    }

    private static ServerLevel resolveLevel(String dimension) {
        if (ChunkBlockLimiter.getServer() == null || dimension == null) return null;
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimension);
        if (dimLoc == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimLoc);
        return ChunkBlockLimiter.getServer().getLevel(key);
    }

    private static String formatBlockName(String blockId) {
        // "minecraft:hopper" -> "Hopper"
        String name = blockId;
        if (name.contains(":")) {
            name = name.substring(name.indexOf(":") + 1);
        }
        name = name.replace("_", " ");
        // Capitalize first letter of each word
        String[] words = name.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1))
                      .append(" ");
            }
        }
        return result.toString().trim();
    }

    private static void send(CommandSourceStack source, Component message) {
        if (ChunkBlockLimiter.adventure() != null && source.getEntity() instanceof ServerPlayer player) {
            ChunkBlockLimiter.adventure().player(player.getUUID()).sendMessage(message);
        } else {
            // Fallback for console
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message)
            ), false);
        }
    }
}
