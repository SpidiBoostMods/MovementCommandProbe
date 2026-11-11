package net.spidiboost.cmprobe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class NetworkProbe {
    @Inject(method="handlePacket",at=@At("HEAD"))
    private static void probe$receive(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        if (!(packet instanceof GameMessageS2CPacket message) || message.overlay() || !(listener instanceof ClientPlayNetworkHandler)) return;
        var client = MinecraftClient.getInstance();
        String text = message.content().getString();
        long received = System.nanoTime()/1_000_000;
        client.execute(() -> {
            if (client.getNetworkHandler() == listener && ProbeClient.instance != null) ProbeClient.instance.receive(text,received);
        });
    }
}
