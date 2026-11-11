package net.spidiboost.cmprobe.qa;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.spidiboost.cmprobe.*;

public class NativeProbe implements ClientModInitializer {
    private long deadline=System.nanoTime()+180_000_000_000L;
    private int step;
    private boolean finished;
    private final AtomicInteger flags=new AtomicInteger(),answers=new AtomicInteger();
    public void onInitializeClient(){
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(
            CommandManager.literal("checkmute").then(CommandManager.argument("nick",StringArgumentType.word()).executes(ctx->{
                var player=ctx.getSource().getPlayerOrThrow();
                var input=player.getPlayerInput();
                if(input.forward()||input.backward()||input.left()||input.right()||input.jump()){
                    flags.incrementAndGet();
                    player.sendMessage(Text.literal("AC » ProbeQA failed ChatC (x1) sprinting=false, sneaking=false, input=true"));
                }else{
                    answers.incrementAndGet();
                    player.sendMessage(Text.literal("Цель не замучена!"));
                }
                return 1;
            }))));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(finished)return;
            try{
                if(System.nanoTime()>deadline)throw new AssertionError("native suite timed out");
                if(client.player==null||client.getServer()==null||client.player.age<60)return;
                client.options.forwardKey.setPressed(true);
                if(step==0){
                    if(client.player.age<70)return;
                    if(ProbeClient.instance==null)throw new AssertionError("entrypoint missing");
                    Class.forName(net.minecraft.network.ClientConnection.class.getName());
                    ClientCommandManager.getActiveDispatcher().execute("cmprobe start",(FabricClientCommandSource)client.getNetworkHandler().getCommandSource());
                    step=1;
                }else if(step==1){
                    var field=ProbeClient.class.getDeclaredField("running");field.setAccessible(true);
                    if(field.getBoolean(ProbeClient.instance))return;
                    var suiteField=ProbeClient.class.getDeclaredField("suite");suiteField.setAccessible(true);
                    var suite=(ProbeSuite)suiteField.get(ProbeClient.instance);
                    if(flags.get()<2)throw new AssertionError("baseline not tested with actual movement input");
                    if(answers.get()<4||suite.winner()==null)throw new AssertionError("reset variants not validated");
                    client.options.forwardKey.setPressed(false);
                    Files.writeString(client.runDirectory.toPath().resolve("result.txt"),"PASS");
                    System.out.println("Native auto suite passed: flags="+flags.get()+" answers="+answers.get()+" winner="+suite.winner().id());
                    finished=true;client.scheduleStop();
                }
            }catch(Throwable e){
                e.printStackTrace();finished=true;client.options.forwardKey.setPressed(false);
                try{Files.writeString(client.runDirectory.toPath().resolve("result.txt"),"FAIL "+e);}catch(Exception ignored){}
                client.scheduleStop();
            }
        });
    }
}
