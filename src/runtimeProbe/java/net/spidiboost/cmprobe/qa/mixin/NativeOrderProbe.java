package net.spidiboost.cmprobe.qa.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.spidiboost.cmprobe.qa.NativeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class NativeOrderProbe {
    @Inject(method="send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V",at=@At("HEAD"))
    private void qa$order(Packet<?> packet,PacketCallbacks callbacks,boolean flush,CallbackInfo ci) {
        NativeProbe.trace(packet);
    }
}
