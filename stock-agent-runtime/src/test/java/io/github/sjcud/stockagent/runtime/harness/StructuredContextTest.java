package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 压缩不改变本轮问题/关键事实，不把半截证据或历史引用当作当前来源。 */
public class StructuredContextTest {
    @Test public void shouldKeepFactsAndWholeHighestScoredEvidence() {
        ContextPolicy policy=new ContextPolicy();
        String facts="证券=600519 日期=2026-10-30 收入=120亿元";
        ContextPolicy.Prepared p=policy.compact("ANSWER","现在收入是多少？",facts,
            Arrays.asList("旧历史[片段=old:v1:1:0]"),Arrays.asList(
                new ContextPolicy.Evidence("low",repeat("低分",100),0.1),new ContextPolicy.Evidence("high","完整证据120亿元",0.9)),260,50);
        assertEquals("现在收入是多少？",p.getQuestion());assertTrue(p.getSupplement().contains(facts));
        assertEquals(Collections.singletonList("high"),p.getSelectedEvidenceIds());
        assertFalse(p.getSupplement().contains("[片段=old:v1:1:0]"));assertTrue(p.getReport().afterEstimate<=260);
    }
    @Test public void shouldRejectProtectedOverflowBeforeAnyModelCall() {
        assertThrows(ContextLimitException.class,()->new ContextPolicy().compact("ANSWER","完整问题不能截断",repeat("关键事实",30),
            Collections.emptyList(),Collections.emptyList(),20,0));
    }
    @Test public void shouldCountUtf8AndRemoveOldestHistoryDeterministically() {
        assertEquals(6,TokenEstimator.estimate("收入"));
        ContextPolicy.Prepared p=new ContextPolicy().compact("ANSWER","q","facts",Arrays.asList(repeat("old",200),"recent"),
            Collections.emptyList(),300,200);
        assertTrue(p.getSupplement().contains("recent"));assertFalse(p.getSupplement().contains(repeat("old",10)));
        assertTrue(p.getReport().afterEstimate<p.getReport().beforeEstimate);
    }
    private String repeat(String s,int count){StringBuilder b=new StringBuilder();for(int i=0;i<count;i++)b.append(s);return b.toString();}
}
