package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import java.util.List;

/**
 * 聊天式研究编排服务。
 *
 * <p>先检索证据，再把行情事实和证据交给分析师；服务不直接依赖 Qdrant
 * 或模型供应商，便于后续替换多 Agent 编排。</p>
 */
public class ResearchChatService {
    private final VectorStore vectorStore;
    private final FundamentalsAnalyst analyst;

    public ResearchChatService(VectorStore vectorStore, FundamentalsAnalyst analyst) {
        if (vectorStore == null || analyst == null) {
            throw new IllegalArgumentException("研究编排依赖不能为空");
        }
        this.vectorStore = vectorStore;
        this.analyst = analyst;
    }

    /** 检索问题相关证据后生成研究回答。 */
    public ResearchChatAnswer ask(String question, MarketSnapshot snapshot) {
        List<RetrievalHit> hits = vectorStore.search(new RetrievalQuery(question, 5));
        StringBuilder evidence = new StringBuilder();
        for (RetrievalHit hit : hits) {
            evidence.append("[片段=").append(hit.getChunkId()).append("] ")
                    .append(hit.getText()).append("；");
        }
        return new ResearchChatAnswer(analyst.analyze(snapshot, evidence.toString()), hits);
    }
}
