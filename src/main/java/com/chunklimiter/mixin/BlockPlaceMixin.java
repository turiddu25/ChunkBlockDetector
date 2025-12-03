package com.chunklimiter.mixin;

import com.chunklimiter.ChunkBlockLimiter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class BlockPlaceMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    // Simple thread-local to track what block we're trying to place
    @Unique
    private static final ThreadLocal<String> chunklimiter$pendingBlockId = new ThreadLocal<>();

    /**
     * HEAD injection: Check if placement should be blocked using actual chunk block count.
     */
    @Inject(
        method = "useItemOn",
        at = @At("HEAD"),
        cancellable = true
    )
    private void chunklimiter$onUseItemOnHead(ServerPlayer player, Level world, ItemStack stack, 
                                               InteractionHand hand, BlockHitResult hitResult, 
                                               CallbackInfoReturnable<InteractionResult> cir) {
        
        // Clear any previous context
        chunklimiter$pendingBlockId.remove();
        
        // Only check for block items
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        // Only process on server
        if (!(world instanceof ServerLevel serverLevel)) {
            return;
        }

        // Get the block that would be placed
        String blockId = blockItem.getBlock().builtInRegistryHolder().key().location().toString();
        
        // Check if this block even has a limit configured
        if (!ChunkBlockLimiter.hasLimit(blockId)) {
            return;
        }

        // Calculate where the block would be placed
        BlockPos placePos = hitResult.getBlockPos();
        BlockState hitState = world.getBlockState(placePos);
        
        // If the hit block isn't replaceable, place adjacent
        if (!hitState.canBeReplaced()) {
            placePos = placePos.relative(hitResult.getDirection());
        }

        // Check if placement should be blocked (uses actual block counting!)
        if (ChunkBlockLimiter.shouldBlockPlacement(player, serverLevel, placePos, blockId)) {
            // Resync inventory to fix visual glitch where item disappears
            player.containerMenu.sendAllDataToRemote();
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        // Store the block ID for RETURN injection
        chunklimiter$pendingBlockId.set(blockId);
    }

    /**
     * RETURN injection: Record successful placement for admin tracking.
     */
    @Inject(
        method = "useItemOn",
        at = @At("RETURN")
    )
    private void chunklimiter$onUseItemOnReturn(ServerPlayer player, Level world, ItemStack stack,
                                                 InteractionHand hand, BlockHitResult hitResult,
                                                 CallbackInfoReturnable<InteractionResult> cir) {
        
        String blockId = chunklimiter$pendingBlockId.get();
        chunklimiter$pendingBlockId.remove();
        
        if (blockId == null) {
            return;
        }

        // Only process on server
        if (!(world instanceof ServerLevel)) {
            return;
        }

        // Check if placement actually succeeded
        InteractionResult result = cir.getReturnValue();
        if (!result.consumesAction()) {
            return;
        }

        // Calculate where the block was placed
        BlockPos placePos = hitResult.getBlockPos();
        BlockState hitState = world.getBlockState(placePos);
        
        // Check if the block at hit position is now our block (replaced something)
        String blockAtHit = hitState.getBlock().builtInRegistryHolder().key().location().toString();
        if (blockAtHit.equals(blockId)) {
            // Block was placed at hit position (replaced something)
            ChunkBlockLimiter.onBlockPlaced(player, placePos, blockId);
        } else {
            // Block was placed adjacent
            BlockPos adjacentPos = hitResult.getBlockPos().relative(hitResult.getDirection());
            BlockState adjacentState = world.getBlockState(adjacentPos);
            String adjacentBlockId = adjacentState.getBlock().builtInRegistryHolder().key().location().toString();
            if (adjacentBlockId.equals(blockId)) {
                ChunkBlockLimiter.onBlockPlaced(player, adjacentPos, blockId);
            }
        }
    }
}