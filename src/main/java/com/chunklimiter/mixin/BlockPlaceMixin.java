package com.chunklimiter.mixin;

import com.chunklimiter.ChunkBlockLimiter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class BlockPlaceMixin {

    @Shadow
    @Final
    protected ServerPlayer player;

    /**
     * Intercept block placement to check limits BEFORE the block is placed.
     */
    @Inject(
        method = "useItemOn",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUseItemOn(ServerPlayer player, Level world, ItemStack stack, 
                             InteractionHand hand, BlockHitResult hitResult, 
                             CallbackInfoReturnable<InteractionResult> cir) {
        // Only check for block items
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        // Calculate where the block would be placed
        BlockPos placePos = hitResult.getBlockPos();
        BlockState hitState = world.getBlockState(placePos);
        
        // If the hit block isn't replaceable, place adjacent
        if (!hitState.canBeReplaced()) {
            placePos = placePos.relative(hitResult.getDirection());
        }

        // Get the block that would be placed
        String blockId = blockItem.getBlock().builtInRegistryHolder().key().location().toString();

        // Check if placement should be blocked
        if (ChunkBlockLimiter.shouldBlockPlacement(player, world, placePos, blockId)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    /**
     * Track successful block placement AFTER it happens.
     */
    @Inject(
        method = "useItemOn",
        at = @At("RETURN")
    )
    private void afterUseItemOn(ServerPlayer player, Level world, ItemStack stack,
                                InteractionHand hand, BlockHitResult hitResult,
                                CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResult result = cir.getReturnValue();
        
        // Only track if placement was successful
        if (result != InteractionResult.SUCCESS && result != InteractionResult.CONSUME) {
            return;
        }

        // We need to find the actual placed block position
        // This is tricky because the stack might have changed
        // We'll handle this in the block break event by checking what block is there
    }
}