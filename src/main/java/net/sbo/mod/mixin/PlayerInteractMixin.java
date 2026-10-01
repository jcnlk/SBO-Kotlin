package net.sbo.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.sbo.mod.diana.automation.DianaAutomation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.sbo.mod.utils.events.SBOEvent;
import net.sbo.mod.utils.events.impl.game.PlayerInteractEvent;
import org.jspecify.annotations.NonNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
final class PlayerInteractMixin {
    @Inject(method = {"startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"))
    private void sbo$selectSpadeForBurrow(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        DianaAutomation.INSTANCE.switchToSpadeForBurrow(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void sbo$restoreAfterBurrowBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            DianaAutomation.INSTANCE.onBurrowBlockBroken(pos);
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private final void sbo$onInteractItem(@NonNull final CallbackInfoReturnable<InteractionResult> cir) {
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            final PlayerInteractEvent event = new PlayerInteractEvent(
                    "useItem", null, player, player.level(), false
            );
            SBOEvent.INSTANCE.emit(event);

            if (event.isCanceled()) {
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private final void sbo$onInteractBlock(@NonNull final LocalPlayer player, @NonNull final InteractionHand hand, @NonNull final BlockHitResult hitResult, @NonNull final CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.MAIN_HAND) {
            final PlayerInteractEvent event = new PlayerInteractEvent(
                    "useBlock", hitResult.getBlockPos(), player, player.level(), false
            );
            SBOEvent.INSTANCE.emit(event);

            if (event.isCanceled()) {
                cir.setReturnValue(InteractionResult.FAIL);
                cir.cancel();
            }
        }
    }
}

