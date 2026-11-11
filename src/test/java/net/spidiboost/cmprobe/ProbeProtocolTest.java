package net.spidiboost.cmprobe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProbeProtocolTest {
    @Test void bindsChatCToSelfAndRejectsPlayerChat(){
        String sample="AC » SpidiBoost failed ChatC (x35) sprinting=false, sneaking=false, input=true";
        assertTrue(ProbeProtocol.chatC(sample,"spidiboost"));
        assertTrue(ProbeProtocol.chatC("[12:10:00] §c"+sample,"SpidiBoost"));
        assertFalse(ProbeProtocol.chatC(sample,"Other"));
        assertFalse(ProbeProtocol.chatC("Somebody » "+sample,"SpidiBoost"));
        assertFalse(ProbeProtocol.chatC(sample.replace("ChatC","ChatCombat"),"SpidiBoost"));
    }
    @Test void muteAnswersHaveExactTargetAndIgnoreUnrelatedChat(){
        for(String line:new String[]{"Цель [A]: заткнута.","Цель A замучена:","Target [A] is muted","Цель не замучена!","Цель не заткнута!"})assertTrue(ProbeProtocol.reply(line,"A"),line);
        assertFalse(ProbeProtocol.reply("Цель [Another] замучена.","A"));
        assertFalse(ProbeProtocol.reply("Цель [A] забанена.","A"));
        assertFalse(ProbeProtocol.reply("Player » Цель не замучена!","A"));
    }
}
