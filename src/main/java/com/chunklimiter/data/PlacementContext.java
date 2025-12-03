package com.chunklimiter.data;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Context for tracking a pending block placement.
 * Used to pass data from HEAD to RETURN injection in BlockPlaceMixin.
 */
public class PlacementContext {
    public final ServerPlayer player;
    public final BlockPos pos;
    public final String blockId;
    public final int stackSizeBefore;
    
    public PlacementContext(ServerPlayer player, BlockPos pos, String blockId, int stackSizeBefore) {
        this.player = player;
        this.pos = pos;
        this.blockId = blockId;
        this.stackSizeBefore = stackSizeBefore;
    }
}