package net.spidiboost.cmprobe;

import java.util.Locale;
import java.util.regex.Pattern;

public final class ProbeProtocol {
    private static final Pattern CHATC=Pattern.compile("(?iu)^AC\\s*»\\s*([a-z0-9_]{1,16})\\s+failed\\s+ChatC\\b.*");
    private static final Pattern TARGET=Pattern.compile("(?iu)^(?:Цель|Target)\\s*:?\\s*(?:\\[([a-z0-9_]{1,16})]|([a-z0-9_]{1,16})(?=\\s))(.*)");
    private static final Pattern EMPTY=Pattern.compile("(?iu)^(?:цель\\s+не\\s+(?:заткнута|замучена|замутена)|target(?: is)? not muted)[.! ]*$");
    private ProbeProtocol(){}
    public static String clean(String line){
        return line.replaceAll("(?i)§[0-9a-fk-orx]","").replace('\u00a0',' ').replaceAll("^\\[\\d{1,2}:\\d{2}:\\d{2}]\\s*","").strip();
    }
    public static boolean chatC(String line,String player){
        var match=CHATC.matcher(clean(line));
        return match.matches()&&match.group(1).equalsIgnoreCase(player);
    }
    public static boolean reply(String line,String player){
        String value=clean(line);
        if(EMPTY.matcher(value).matches())return true;
        var match=TARGET.matcher(value);
        if(!match.matches())return false;
        String target=match.group(1)==null?match.group(2):match.group(1);
        String lower=match.group(3).toLowerCase(Locale.ROOT);
        return target.equalsIgnoreCase(player)&&(lower.contains("заткнут")||lower.contains("замуч")||lower.contains("замут")||lower.contains("muted"));
    }
}
