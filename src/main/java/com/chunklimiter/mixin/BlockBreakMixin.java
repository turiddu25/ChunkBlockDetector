package com.chunklimiter.mixin;

import com.chunklimiter.ChunkBlockLimiter;
import com.chunklimiter.data.BreakContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class BlockBreakMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    // Thread-local to pass block state from HEAD to RETURN
    @Unique
    private static final ThreadLocal<BreakContext> chunklimiter$pendingBreak = new ThreadLocal<>();

    /**
     * HEAD injection: Capture the block state BEFORE the block is broken.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("HEAD")
    )
    private void chunklimiter$onBlockBreakHead(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Clear any previous context
        chunklimiter$pendingBreak.remove();
        
        // Capture block state before it's destroyed
        BlockState blockState = level.getBlockState(pos);
        
        // Only track if this block has a limit
        String blockId = blockState.getBlock().builtInRegistryHolder().key().location().toString();
        if (!ChunkBlockLimiter.hasLimit(blockId)) {
            return;
        }
        
        chunklimiter$pendingBreak.set(new BreakContext(player, pos.immutable(), blockState));
    }

    /**
     * RETURN injection: Update tracking and invalidate cache if block was broken.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("RETURN")
    )
    private void chunklimiter$onBlockBreakReturn(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BreakContext ctx = chunklimiter$pendingBreak.get();
        chunklimiter$pendingBreak.remove();
        
        if (ctx == null) {
            return;
        }

        // Only process if block was actually broken
        Boolean result = cir.getReturnValue();
        if (result != null && result) {
            // Block was successfully broken - update tracking and cache
            ChunkBlockLimiter.onBlockBroken(ctx.player, ctx.pos, ctx.blockState);
        }
    }
}