package dev.example.mcauth.server.mixin;

import dev.example.mcauth.server.AuthServerMod;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class AuthPacketGateMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$containerClick(ServerboundContainerClickPacket packet, CallbackInfo callback) {
        deny(callback);
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$containerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo callback) {
        deny(callback);
    }

    @Inject(method = "handleContainerSlotStateChanged", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$containerSlotState(ServerboundContainerSlotStateChangedPacket packet, CallbackInfo callback) {
        deny(callback);
    }

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$placeRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo callback) {
        deny(callback);
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$creativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) {
        deny(callback);
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void mctgAuth$playerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        deny(callback);
    }

    private void deny(CallbackInfo callback) {
        if (!AuthServerMod.isAuthenticated(player)) callback.cancel();
    }
}
