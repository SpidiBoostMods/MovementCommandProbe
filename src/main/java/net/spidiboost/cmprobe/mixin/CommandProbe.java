package net.spidiboost.cmprobe.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.spidiboost.cmprobe.ProbeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class CommandProbe {
    @Inject(method="sendChatCommand",at=@At("HEAD"))
    private void probe$otherCommand(String command,CallbackInfo ci) {
        if(ProbeClient.instance!=null)ProbeClient.instance.externalCommand(command);
    }
}
