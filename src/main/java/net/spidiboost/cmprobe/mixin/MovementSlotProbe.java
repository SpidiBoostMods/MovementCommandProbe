package net.spidiboost.cmprobe.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.spidiboost.cmprobe.ProbeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MovementSlotProbe {
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/client/network/ClientPlayerEntity;sendSneakingPacket()V"))
    private void probe$start(CallbackInfo ci) {
        if(ProbeClient.instance!=null)ProbeClient.instance.networkSlot(false);
    }
    @Inject(method="sendMovementPackets",at=@At(value="INVOKE",target="Lnet/minecraft/client/network/ClientPlayerEntity;sendSprintingPacket()V",shift=At.Shift.AFTER))
    private void probe$beforeMove(CallbackInfo ci) {
        if(ProbeClient.instance!=null)ProbeClient.instance.networkSlot(true);
    }
}
