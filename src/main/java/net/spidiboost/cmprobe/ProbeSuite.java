package net.spidiboost.cmprobe;

import java.util.*;

public final class ProbeSuite {
    public record Variant(String id, long interval, int resetTicks) {}
    public static final List<Variant> VARIANTS = List.of(
        new Variant("ordinary-1000",1000,-1),
        new Variant("ordinary-2000",2000,-1),
        new Variant("reset-now-1000",1000,0),
        new Variant("reset-now-500",500,0),
        new Variant("reset-now-250",250,0),
        new Variant("reset-1tick-1000",1000,1),
        new Variant("reset-1tick-500",500,1),
        new Variant("reset-2ticks-1000",1000,2)
    );
    public static final class Stats {
        public int replies, failures, tabReplies;
    }
    private final Map<Variant,Stats> results=new LinkedHashMap<>();
    private final List<String> names;
    private int variantIndex, sampleName, tabIndex, caseReplies;
    private Variant current;
    private boolean tab, finished;
    private Variant winner;

    public ProbeSuite(List<String> names) {
        if(names.isEmpty())throw new IllegalArgumentException("TAB пуст.");
        this.names=List.copyOf(names);
        VARIANTS.forEach(v->results.put(v,new Stats()));
        current=VARIANTS.getFirst();
    }
    public Variant current(){return current;}
    public String target(){return tab?names.get(tabIndex):names.get(sampleName%names.size());}
    public String phase(){return tab?"tab-validation":"sampling";}
    public boolean finished(){return finished;}
    public Variant winner(){return winner;}
    public Map<Variant,Stats> results(){return Collections.unmodifiableMap(results);}
    public int tabSize(){return names.size();}
    public int tabDone(){return tabIndex;}

    public void result(boolean success) {
        if(finished)return;
        Stats stats=results.get(current);
        if(success) {
            stats.replies++;
            if(tab) {
                stats.tabReplies++;
                if(++tabIndex==names.size()){winner=current;finished=true;}
            } else if(++caseReplies==3)nextCase();
        } else {
            stats.failures++;
            if(tab)selectCandidate();else nextCase();
        }
        sampleName++;
    }
    public void invalidate(Variant variant) {
        if(variant==null)return;
        results.get(variant).failures++;
        if(tab&&current.equals(variant)){winner=null;finished=false;selectCandidate();}
    }
    private void nextCase() {
        caseReplies=0;
        if(++variantIndex<VARIANTS.size())current=VARIANTS.get(variantIndex);
        else {tab=true;selectCandidate();}
    }
    private void selectCandidate() {
        tabIndex=0;
        current=results.entrySet().stream()
            .filter(e->e.getValue().failures==0&&e.getValue().replies>=3)
            .map(Map.Entry::getKey)
            .min(Comparator.comparingLong(Variant::interval).thenComparingInt(Variant::resetTicks))
            .orElse(null);
        if(current==null)finished=true;
    }
    public String summary() {
        var text=new StringBuilder();
        if(winner!=null)text.append("Кандидат прошёл TAB: ").append(winner.id()).append("\n");
        else text.append("Подтверждённого варианта для всего TAB нет.\n");
        text.append("Проверяются обычные команды и временный сброс ввода перед отправкой.\n")
            .append("Один проход отражает условия этого запуска и не гарантирует будущие результаты.\n\n");
        for(var entry:results.entrySet()){
            var variant=entry.getKey();var stats=entry.getValue();
            text.append(variant.id()).append(" | интервал ").append(variant.interval())
                .append(" мс | сброс ").append(variant.resetTicks()<0?"нет":variant.resetTicks()+" тиков")
                .append(" | ответов ").append(stats.replies).append(" | ошибок ").append(stats.failures)
                .append(" | ответов TAB ").append(stats.tabReplies).append("\n");
        }
        return text.toString();
    }
}
