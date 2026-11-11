package net.spidiboost.cmprobe;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProbeSuiteTest {
    @Test void inputResetAloneCannotQualifyWhenSeparateSprintFlagRemains(){
        var suite=new ProbeSuite(List.of("A"));
        while(suite.phase().equals("sampling")) {
            boolean full=suite.current().stopFlags();
            if(full){suite.result(true);suite.result(true);suite.result(true);}
            else suite.result(false);
        }
        assertTrue(suite.current().stopFlags());
        suite.result(true);
        assertTrue(suite.finished());assertTrue(suite.winner().stopFlags());
    }
    @Test void firstFailureAdvancesAndDoesNotEndTheSuite(){
        var suite=new ProbeSuite(List.of("A","B"));
        var first=suite.current();suite.result(false);
        assertFalse(suite.finished());assertNotEquals(first,suite.current());
        assertEquals(1,suite.results().get(first).failures);
    }
    @Test void bestCandidateMustRepeatAcrossAllTabAndFallsBackOnFailure(){
        var suite=new ProbeSuite(List.of("A","B"));
        for(var variant:ProbeSuite.VARIANTS)for(int i=0;i<3;i++)suite.result(true);
        assertEquals("tab-validation",suite.phase());
        assertEquals(250,suite.current().interval());
        assertEquals("A",suite.target());suite.result(true);
        assertEquals("B",suite.target());var chosen=suite.current();suite.result(false);
        assertNotEquals(chosen,suite.current());
        assertEquals("A",suite.target());
        suite.result(true);suite.result(true);
        assertTrue(suite.finished());assertNotNull(suite.winner());
        assertTrue(suite.winner().stopFlags());
    }
    @Test void everyFailureFinishesWithoutInventingAWinner(){
        var suite=new ProbeSuite(List.of("A"));
        for(var ignored:ProbeSuite.VARIANTS)suite.result(false);
        assertTrue(suite.finished());assertNull(suite.winner());
        assertTrue(suite.summary().contains("Подтверждённого варианта"));
    }
    @Test void delayedFlagRemovesAnAlreadySampledCandidate(){
        var suite=new ProbeSuite(List.of("A"));
        for(var ignored:ProbeSuite.VARIANTS)for(int i=0;i<3;i++)suite.result(true);
        var chosen=suite.current();suite.invalidate(chosen);
        assertNotEquals(chosen,suite.current());assertEquals(1,suite.results().get(chosen).failures);
    }
}
