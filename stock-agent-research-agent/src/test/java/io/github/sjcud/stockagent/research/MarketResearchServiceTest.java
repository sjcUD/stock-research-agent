package io.github.sjcud.stockagent.research;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证服务端行情进入角色输入且不混入未知时点的 RAG 文档。 */
public class MarketResearchServiceTest {
    @Test
    public void shouldUseProviderFactsAndKeepCitationsInResponse() throws Exception {
        VectorStore store = new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { }
            public List<RetrievalHit> search(RetrievalQuery query) {
                fail("托管市场研究不得自动混入未知时点的样例文档"); return Collections.emptyList();
            }
        };
        MultiResearchService multi = new MultiResearchService(store, DefaultAnalystFactory.create(prompt -> prompt),
                new PortfolioManager(prompt -> "汇总"));
        MarketResearchAnswer answer = new MarketResearchService(new SampleStockProvider(),multi)
                .ask("指标和新闻如何", "600519", "2026-10-30", ResearchMode.PARALLEL);
        assertEquals(DataMode.SAMPLE,answer.getMarketData().getMode());
        assertEquals("1680.00",answer.getMarketData().getSnapshot().getClose());
        assertEquals(6,answer.getResearch().getTraces().size());
        assertTrue(answer.getResearch().getTraces().get(0).getInput().contains("SMA20"));
        assertTrue(answer.getResearch().getTraces().get(0).getInput().contains("sample-news"));
        assertTrue(answer.getResearch().getEvidence().isEmpty());
    }

    @Test
    public void shouldIncludeSharedEvidenceOnlyOnceInManagerPrompt() {
        PortfolioDecision decision = new PortfolioManager(prompt -> prompt).decide("问题", Arrays.asList(
                new AnalystResult(AnalystRole.NEWS,"新闻结论","唯一共享证据"),
                new AnalystResult(AnalystRole.RISK,"风险结论","唯一共享证据")));
        String text = decision.getDecision();
        assertEquals(text.indexOf("唯一共享证据"),text.lastIndexOf("唯一共享证据"));
    }
}
