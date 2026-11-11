package net.spidiboost.cmprobe;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.PlayerInput;

public class ProbeClient implements ClientModInitializer {
    public static ProbeClient instance;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"CommandProbe-files");thread.setDaemon(true);return thread;
    });
    private final List<String> rows=new ArrayList<>();
    private final ArrayDeque<String> manualNames=new ArrayDeque<>();
    private ProbeSuite suite;
    private ProbeSuite.Variant variant, lastVariant;
    private Object connection;
    private boolean running, manualIdle, response, pulse, prepared, ownCommand;
    private long frames, preparedFrame, restoreFrame, sent, answeredAt, lastSend, lastMotion, cooldown, noMotionSince;
    private long delay=1000,settle=250,resetStarted;
    private String pending="",lastTarget="",rawReply="";
    private Path report;
    private String samplePrefix="";
    private int rowCount;
    private static long now(){return System.nanoTime()/1_000_000;}

    @Override public void onInitializeClient() {
        instance=this;
        ClientTickEvents.END_CLIENT_TICK.register(c->{
            try{tick();}catch(RuntimeException e){finish("Ошибка теста: "+e.getClass().getSimpleName());}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h,c)->finish("Соединение закрыто"));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c->{
            finish("Игра закрывается");writer.shutdown();
            try{writer.awaitTermination(2,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->dispatcher.register(
            ClientCommandManager.literal("cmprobe").executes(ctx->help())
                .then(ClientCommandManager.argument("options",StringArgumentType.greedyString())
                    .executes(ctx->command(StringArgumentType.getString(ctx,"options"))))));
    }
    private void message(String text) {
        var player=MinecraftClient.getInstance().player;
        if(player!=null)player.sendMessage(Text.literal("[CMProbe] "+text),false);
    }
    private int help(){message("/cmprobe start · start manual · start idle · stop · open · csv · delay мс · settle мс");return 1;}
    private int command(String text) {
        String[] words=text.strip().toLowerCase(Locale.ROOT).split("\\s+");
        try {
            switch(words[0]) {
                case "start" -> {
                    if(words.length>2||words.length==2&&!Set.of("manual","idle").contains(words[1]))return help();
                    start(words.length==1,words.length==2&&words[1].equals("idle"));
                }
                case "stop" -> finish("Остановлено");
                case "open" -> open(false);
                case "csv" -> open(true);
                case "delay","settle" -> {
                    if(words.length!=2)return help();
                    long value=Long.parseLong(words[1]);
                    if(value<0||value>60000||words[0].equals("delay")&&value<250)throw new IllegalArgumentException("delay — 250–60000 мс, settle — 0–60000 мс.");
                    if(running)throw new IllegalStateException("Сначала /cmprobe stop.");
                    if(words[0].equals("delay"))delay=value;else settle=value;
                    message(words[0]+" = "+value+" мс, ручные режимы.");
                }
                default -> {return help();}
            }
        }catch(Exception e){message(e.getMessage()==null?"Неверные параметры":e.getMessage());}
        return 1;
    }
    private void start(boolean automatic,boolean idle) {
        var client=MinecraftClient.getInstance();
        if(running)throw new IllegalStateException("Проверка уже идёт. /cmprobe stop");
        if(client.player==null||client.getNetworkHandler()==null)throw new IllegalStateException("Подключись к серверу.");
        var names=new LinkedHashMap<String,String>();
        for(var entry:client.getNetworkHandler().getListedPlayerListEntries()){
            String name=entry.getProfile().getName();
            if(name.matches("[A-Za-z0-9_]{1,16}"))names.putIfAbsent(name.toLowerCase(Locale.ROOT),name);
        }
        if(names.isEmpty())throw new IllegalStateException("TAB пуст.");
        suite=automatic?new ProbeSuite(List.copyOf(names.values())):null;
        manualNames.clear();manualNames.addAll(names.values());
        rows.clear();
        rows.add("nick,phase,variant,configured_interval_ms,actual_interval_ms,reset_ticks,reset_wait_ms,movement_keys,forward,backward,left,right,jump,sprint_input,sneak_input,sprinting,sneaking,horizontal_speed,stationary_ms,status,response_ms,server_text");
        rowCount=0;pending=lastTarget=rawReply="";lastVariant=null;
        prepared=pulse=response=false;restoreFrame=-1;
        manualIdle=idle;running=true;lastSend=0;
        lastMotion=noMotionSince=now();cooldown=now()+1500;connection=client.getNetworkHandler();
        report=client.runDirectory.toPath().resolve("logs/command-probe/"+LocalDateTime.now().toString().replace(':','-')+".csv");
        message(automatic?"Автотест начат. Двигайся, способы и интервалы перебираются автоматически.":"Ручной тест начат.");
    }
    private PlayerInput input(){return MinecraftClient.getInstance().player.input.playerInput;}
    private boolean moving(PlayerInput input){return input.forward()||input.backward()||input.left()||input.right()||input.jump();}
    private void resetInput() {
        var client=MinecraftClient.getInstance();
        pulse=true;resetStarted=now();
        client.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(PlayerInput.DEFAULT));
    }
    private void restoreInput() {
        if(!pulse)return;
        pulse=false;prepared=false;restoreFrame=-1;
        var client=MinecraftClient.getInstance();
        if(client.player!=null&&client.getNetworkHandler()==connection){
            try{client.getNetworkHandler().sendPacket(new PlayerInputC2SPacket(input()));}
            catch(RuntimeException e){message("Не удалось восстановить ввод: "+e.getClass().getSimpleName());}
        }
    }
    public void externalCommand(String command) {
        if(running&&!ownCommand&&command.strip().toLowerCase(Locale.ROOT).startsWith("checkmute "))
            finish("Другая проверка отправила /checkmute. Замер остановлен для сохранения соответствия ответов.");
    }
    private void tick() {
        frames++;
        if(pulse&&restoreFrame>=0&&frames>=restoreFrame)restoreInput();
        if(!running)return;
        var client=MinecraftClient.getInstance();long time=now();
        if(client.player==null||client.getNetworkHandler()!=connection){finish("Соединение изменилось");return;}
        PlayerInput actual=input();
        double speed=Math.hypot(client.player.getVelocity().x,client.player.getVelocity().z);
        if(moving(actual)||speed>0.01)lastMotion=time;
        if(!pending.isEmpty()){
            if(response&&time-answeredAt>=150)completeSample(true,"reply",answeredAt-sent,rawReply);
            else if(!response&&time-sent>=4000)completeSample(false,"timeout",time-sent,"");
            return;
        }
        if(suite!=null&&suite.finished()){finish("Автотест завершён");return;}
        if(suite==null&&manualNames.isEmpty()){finish("Готово");return;}
        if(client.currentScreen!=null||time<cooldown){if(prepared)restoreInput();return;}
        variant=suite==null?new ProbeSuite.Variant(manualIdle?"manual-idle":"manual",delay,-1):suite.current();
        if(time-lastSend<variant.interval())return;
        if(suite!=null&&!moving(actual)){
            if(prepared)restoreInput();
            if(time-noMotionSince>=20000)finish("Нет движения 20 секунд. Данных для продолжения недостаточно.");
            return;
        }
        noMotionSince=time;
        if(suite==null&&manualIdle&&time-lastMotion<settle)return;
        if(variant.resetTicks()>=0&&!prepared){
            prepared=true;preparedFrame=frames;resetInput();
        }
        if(prepared&&frames-preparedFrame<variant.resetTicks())return;
        send(actual,speed,time);
    }
    private static String csv(Object value){return "\""+String.valueOf(value).replace("\"","\"\"").replace("\n"," ").replace("\r"," ")+"\"";}
    private void send(PlayerInput actual,double speed,long time) {
        var client=MinecraftClient.getInstance();
        pending=suite==null?manualNames.removeFirst():suite.target();
        lastTarget=pending;lastVariant=variant;response=false;rawReply="";
        String phase=suite==null?"manual":suite.phase();
        samplePrefix=String.join(",",List.of(csv(pending),csv(phase),csv(variant.id()),csv(variant.interval()),csv(lastSend==0?0:time-lastSend),csv(variant.resetTicks()),csv(pulse?time-resetStarted:0),
            csv(moving(actual)),csv(actual.forward()),csv(actual.backward()),csv(actual.left()),csv(actual.right()),csv(actual.jump()),csv(actual.sprint()),csv(actual.sneak()),
            csv(client.player.isSprinting()),csv(client.player.isSneaking()),csv(String.format(Locale.ROOT,"%.5f",speed)),csv(time-lastMotion)));
        sent=lastSend=time;prepared=false;
        if(pulse)restoreFrame=frames+1;
        try{
            ownCommand=true;
            client.getNetworkHandler().sendChatCommand("checkmute "+pending);
        }catch(Exception e){record("send_error",0,e.getClass().getSimpleName());pending="";finish("Ошибка отправки");}
        finally{ownCommand=false;}
    }
    private void record(String status,long latency,String line) {
        rows.add(samplePrefix+","+csv(status)+","+csv(Math.max(0,latency))+","+csv(line));rowCount++;
    }
    private void completeSample(boolean success,String status,long latency,String line) {
        record(status,latency,line);pending="";response=false;restoreInput();
        if(suite==null){
            if(!success)finish("Ручной тест остановлен: "+status);
        } else {
            var before=suite.current();String phase=suite.phase();suite.result(success);
            cooldown=now()+(success?0:2500);
            if(!suite.finished()&&(!suite.current().equals(before)||!suite.phase().equals(phase)))
                message((suite.phase().equals("sampling")?"Проверяем ":"Повторная проверка TAB: ")+suite.current().id());
        }
    }
    public void receive(String packet,long received) {
        if(!running)return;
        var client=MinecraftClient.getInstance();
        for(String part:packet.split("\\R")){
            String line=ProbeProtocol.clean(part);
            String failure=client.player==null?"":ProbeProtocol.failure(line,client.player.getGameProfile().getName());
            if(!failure.isEmpty()){
                String status="AC_"+failure;
                if(!pending.isEmpty())completeSample(false,response?"reply_and_"+status:status,received-sent,line);
                else if(lastVariant!=null){
                    record("late_"+status,received-sent,line);
                    if(suite!=null){suite.invalidate(lastVariant);cooldown=now()+2500;}
                    else finish("AC сообщил "+failure);
                }
                return;
            }
            if(pending.isEmpty()||received<sent)continue;
            String lower=line.toLowerCase(Locale.ROOT);
            if(lower.matches("^(?:нет прав|недостаточно прав|у вас нет прав|you do not have permission).*")){
                record("permission_denied",received-sent,line);pending="";finish("Нет прав на /checkmute");return;
            }
            if(lower.matches("^(?:слишком быстро|подождите|slow down|too fast).*")){
                completeSample(false,"rate_limited",received-sent,line);cooldown=now()+5000;return;
            }
            if(ProbeProtocol.reply(line,pending)&&!response){response=true;answeredAt=received;rawReply=line;}
        }
    }
    private void finish(String reason) {
        if(!running)return;
        if(!pending.isEmpty()){record(response?"reply_before_stop":"cancelled",response?answeredAt-sent:now()-sent,rawReply);pending="";}
        running=false;restoreInput();manualNames.clear();
        Path file=report;String data=String.join("\n",rows)+"\n";
        String summary=reason+"\n"+(suite==null?"Ручной режим.\n":suite.summary())+"\nCSV: "+file.toAbsolutePath()+"\nСтрок: "+rowCount+"\n";
        writer.execute(()->{
            try{
                Files.createDirectories(file.getParent());
                Files.writeString(file,data,StandardCharsets.UTF_8);
                Files.writeString(summaryPath(file),summary,StandardCharsets.UTF_8);
                MinecraftClient.getInstance().execute(()->message(reason+". Отчёт: /cmprobe open, данные: /cmprobe csv"));
            }catch(Exception e){MinecraftClient.getInstance().execute(()->message("Не удалось сохранить отчёт: "+e.getMessage()));}
        });
    }
    private static Path summaryPath(Path csv){return csv.resolveSibling(csv.getFileName().toString().replace(".csv",".txt"));}
    private void open(boolean raw) {
        if(report==null){message("Сначала запусти проверку.");return;}
        Path file=raw?report:summaryPath(report);
        writer.execute(()->{
            try{
                if(!Files.isRegularFile(file))throw new IllegalStateException("Отчёт ещё не сохранён.");
                String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
                List<String> command=os.startsWith("windows")?List.of("notepad.exe",file.toAbsolutePath().toString()):os.contains("mac")?List.of("/usr/bin/open","-a","TextEdit",file.toAbsolutePath().toString()):List.of("xdg-open",file.toAbsolutePath().toString());
                new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }catch(Exception e){MinecraftClient.getInstance().execute(()->message(e.getMessage()));}
        });
    }
}
