package net.sbo.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.sbo.mod.diana.automation.SpadeDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Inventory.class, priority = 2000)
final class SpadeSlotDiagnosticsMixin {
    @Shadow private int selected;
    @Inject(method = "setSelectedSlot", at = @At("HEAD"))
    private void sbo$traceSlotChange(int slot, CallbackInfo ci) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.getInventory() != (Object) this) return;
        SpadeDiagnostics.INSTANCE.slotChange(selected, slot);
    }
}
