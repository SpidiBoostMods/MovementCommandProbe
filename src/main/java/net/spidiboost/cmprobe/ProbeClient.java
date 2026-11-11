package net.spidiboost.cmprobe;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import com.mojang.brigadier.arguments.StringArgumentType;

public class ProbeClient implements ClientModInitializer {
    public static ProbeClient instance;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final List<String> rows = new ArrayList<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"CommandProbe-files");t.setDaemon(true);return t;});
    private boolean running, idle, movingAtSend, response;
    private String pending = "", rawReply = "", lastTarget = "";
    private long sent, lastSend, lastMotion, stoppedFor, delay = 1000, settle = 250, answeredAt;
    private double speedAtSend;
    private boolean sprintAtSend, sneakAtSend;
    private int total, done, accepted, timeout;
    private Object connection;
    private Path report;
    private static long now(){return System.nanoTime()/1_000_000;}
    @Override public void onInitializeClient(){
        instance=this;
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            finish("Игра закрывается");
            writer.shutdown();
            try { writer.awaitTermination(2,TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((h,c) -> finish("Соединение закрыто"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access) -> dispatcher.register(
            ClientCommandManager.literal("cmprobe").executes(ctx -> help())
                .then(ClientCommandManager.argument("options",StringArgumentType.greedyString()).executes(ctx -> command(StringArgumentType.getString(ctx,"options"))))));
    }
    private void message(String text){var p=MinecraftClient.getInstance().player;if(p!=null)p.sendMessage(Text.literal("[CMProbe] "+text),false);}
    private int help(){message("/cmprobe start [idle] · stop · delay мс · settle мс · open");return 1;}
    private int command(String text){
        String[] words=text.strip().toLowerCase(Locale.ROOT).split("\\s+");
        try {
            switch(words[0]){
                case "start" -> {if(words.length>2||words.length==2&&!words[1].equals("idle"))return help();start(words.length==2);}
                case "stop" -> finish("Остановлено");
                case "open" -> open();
                case "delay","settle" -> {
                    if(words.length!=2)return help();
                    long value=Long.parseLong(words[1]);
                    if(value<0||value>60000||words[0].equals("delay")&&value<250)throw new IllegalArgumentException("delay — 250–60000 мс, settle — 0–60000 мс.");
                    if(words[0].equals("delay"))delay=value;else settle=value;
                    message(words[0]+" = "+value+" мс");
                }
                default -> {return help();}
            }
        } catch(Exception e){message(e.getMessage()==null?"Неверные параметры":e.getMessage());}
        return 1;
    }
    private void start(boolean idleOnly){
        var c=MinecraftClient.getInstance();
        if(running){message("Проверка уже идёт. /cmprobe stop");return;}
        if(c.player==null||c.getNetworkHandler()==null){message("Подключись к серверу.");return;}
        var names=new LinkedHashMap<String,String>();
        for(var entry:c.getNetworkHandler().getListedPlayerListEntries()){
            String name=entry.getProfile().getName();
            if(name.matches("[A-Za-z0-9_]{1,16}"))names.putIfAbsent(name.toLowerCase(Locale.ROOT),name);
        }
        queue.clear();queue.addAll(names.values());
        if(queue.isEmpty()){message("TAB пуст.");return;}
        rows.clear();rows.add("nick,mode,interval_ms,settle_ms,movement_keys,sprinting,sneaking,horizontal_speed,stationary_ms,status,response_ms,server_text");
        total=queue.size();done=accepted=timeout=0;pending="";idle=idleOnly;running=true;response=false;lastSend=0;lastMotion=now();
        connection=c.getNetworkHandler();
        report=c.runDirectory.toPath().resolve("logs/command-probe/"+LocalDateTime.now().toString().replace(':','-')+".csv");
        message("Ников "+total+" · "+(idle?"после остановки":"обычная отправка")+" · интервал "+delay+" мс. При ChatC тест остановится.");
    }
    private boolean movement(){
        var c=MinecraftClient.getInstance();
        return c.options.forwardKey.isPressed()||c.options.backKey.isPressed()||c.options.leftKey.isPressed()||c.options.rightKey.isPressed()||c.options.jumpKey.isPressed();
    }
    private double speed(){var v=MinecraftClient.getInstance().player.getVelocity();return Math.hypot(v.x,v.z);}
    private void tick(){
        if(!running)return;
        var c=MinecraftClient.getInstance();long time=now();
        if(c.player==null||c.getNetworkHandler()!=connection){finish("Соединение изменилось");return;}
        boolean moving=movement();double speed=speed();
        if(moving||speed>0.01)lastMotion=time;
        if(!pending.isEmpty()){
            if(response&&time-answeredAt>=150){record("reply",answeredAt-sent,rawReply);pending="";response=false;accepted++;}
            else if(!response&&time-sent>=4000){record("timeout",time-sent,"");timeout++;pending="";finish("Нет ответа за 4 секунды");}
            return;
        }
        if(queue.isEmpty()){finish("Готово");return;}
        if(c.currentScreen!=null||time-lastSend<delay||idle&&time-lastMotion<settle)return;
        pending=queue.removeFirst();lastTarget=pending;movingAtSend=moving;speedAtSend=speed;stoppedFor=time-lastMotion;
        sprintAtSend=c.player.isSprinting();sneakAtSend=c.player.isSneaking();sent=lastSend=time;rawReply="";
        try{c.getNetworkHandler().sendChatCommand("checkmute "+pending);}
        catch(Exception e){record("send_error",0,e.getClass().getSimpleName());pending="";finish("Ошибка отправки");}
    }
    public void receive(String packet,long received){
        if(!running)return;
        var c=MinecraftClient.getInstance();
        for(String part:packet.split("\\R")){
            String line=ProbeProtocol.clean(part);
            if(c.player!=null&&ProbeProtocol.chatC(line,c.player.getGameProfile().getName())){
                if(pending.isEmpty())pending=lastTarget;
                record(response?"reply_and_ChatC":"ChatC",received-sent,line);pending="";
                finish("AC сообщил ChatC");return;
            }
            if(pending.isEmpty()||received<sent)continue;
            String lower=line.toLowerCase(Locale.ROOT);
            if(lower.matches("^(?:нет прав|недостаточно прав|у вас нет прав|слишком быстро|подождите|you do not have permission).*")){
                record("rejected",received-sent,line);pending="";finish("Сервер отклонил команду");return;
            }
            if(ProbeProtocol.reply(line,pending)){
                if(!response){response=true;answeredAt=received;rawReply=line;}
            }
        }
    }
    private static String csv(Object value){return "\""+String.valueOf(value).replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\"";}
    private void record(String status,long latency,String text){
        rows.add(String.join(",",List.of(csv(pending),csv(idle?"idle":"normal"),csv(delay),csv(settle),csv(movingAtSend),csv(sprintAtSend),csv(sneakAtSend),csv(String.format(Locale.ROOT,"%.5f",speedAtSend)),csv(stoppedFor),csv(status),csv(Math.max(0,latency)),csv(text))));done++;
    }
    private void finish(String reason){
        if(!running)return;
        if(!pending.isEmpty()){record(response?"reply":"cancelled",response?answeredAt-sent:now()-sent,rawReply);pending="";}
        running=false;queue.clear();
        int finishedDone=done, finishedTotal=total, finishedAccepted=accepted, finishedTimeout=timeout;
        Path file=report;String data=String.join("\n",rows)+"\n";
        writer.execute(()->{try{Files.createDirectories(file.getParent());Files.writeString(file,data,StandardCharsets.UTF_8);MinecraftClient.getInstance().execute(()->message(reason+" · записей "+finishedDone+"/"+finishedTotal+" · ответы "+finishedAccepted+" · таймауты "+finishedTimeout+". Отчёт: /cmprobe open"));}
            catch(Exception e){MinecraftClient.getInstance().execute(()->message("Не удалось сохранить отчёт: "+e.getMessage()));}});
    }
    private void open(){
        if(report==null){message("Сначала запусти проверку.");return;}
        Path file=report;
        writer.execute(()->{try{
            if(!Files.isRegularFile(file))throw new IllegalStateException("Отчёт ещё не сохранён.");
            String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
            List<String> command=os.startsWith("windows")?List.of("notepad.exe",file.toAbsolutePath().toString()):os.contains("mac")?List.of("/usr/bin/open","-a","TextEdit",file.toAbsolutePath().toString()):List.of("xdg-open",file.toAbsolutePath().toString());
            new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        }catch(Exception e){MinecraftClient.getInstance().execute(()->message(e.getMessage()));}});
    }
}
