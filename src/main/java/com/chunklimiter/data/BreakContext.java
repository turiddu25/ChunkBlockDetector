package com.chunklimiter.data;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Context for tracking a pending block break.
 * Used to pass data from HEAD to RETURN injection in BlockBreakMixin.
 */
public class BreakContext {
    public final ServerPlayer player;
    public final BlockPos pos;
    public final BlockState blockState;
    
    public BreakContext(ServerPlayer player, BlockPos pos, BlockState blockState) {
        this.player = player;
        this.pos = pos;
        this.blockState = blockState;
    }
}