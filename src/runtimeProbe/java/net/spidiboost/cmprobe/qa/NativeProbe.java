package net.spidiboost.cmprobe.qa;

import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.gui.screen.TitleScreen;
import net.spidiboost.cmprobe.ProbeClient;
import net.spidiboost.cmprobe.ProbeProtocol;

public class NativeProbe implements ClientModInitializer {
    private int frames;
    private boolean finished;
    public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(finished||++frames<60||!(client.currentScreen instanceof TitleScreen)||client.getOverlay()!=null)return;
            finished=true;
            try {
                if(ProbeClient.instance==null)throw new AssertionError("entrypoint not initialized");
                Class.forName("net.spidiboost.cmprobe.ProbeProtocol");
                Class.forName(net.minecraft.network.ClientConnection.class.getName());
                if(!ProbeProtocol.chatC("AC » ProbeQA failed ChatC (x1) input=true","ProbeQA"))throw new AssertionError("protocol not loaded");
                var dispatcher=ClientCommandManager.getActiveDispatcher();
                if(dispatcher!=null&&dispatcher.getRoot().getChild("cmprobe")==null)throw new AssertionError("command missing");
                Files.writeString(client.runDirectory.toPath().resolve("result.txt"),"PASS");
            }catch(Throwable e){
                e.printStackTrace();
                try{Files.writeString(client.runDirectory.toPath().resolve("result.txt"),"FAIL "+e);}catch(Exception ignored){}
            }
            client.scheduleStop();
        });
    }
}
