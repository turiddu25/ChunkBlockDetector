package com.chunklimiter.mixin;

import com.chunklimiter.ChunkBlockLimiter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    /**
     * Track block breaking to decrement counts.
     * We capture the block state BEFORE it's broken to know what block it was.
     */
    @Inject(
        method = "destroyBlock",
        at = @At("HEAD")
    )
    private void onBlockBreakHead(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Get block state before it's destroyed
        BlockState blockState = level.getBlockState(pos);
        ChunkBlockLimiter.onBlockBroken(player, pos, blockState);
    }
}