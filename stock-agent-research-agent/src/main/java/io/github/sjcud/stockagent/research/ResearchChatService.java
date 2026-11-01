package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.TimedVectorStore;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.runtime.harness.BudgetLedger;
import io.github.sjcud.stockagent.runtime.harness.HarnessCallExecutor;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 聊天式研究编排服务。
 *
 * <p>先检索证据，再把行情事实和证据交给分析师；服务不直接依赖 Qdrant 或模型供应商，便于后续替换多 Agent 编排。
 */
public class ResearchChatService {
    private final VectorStore vectorStore;
    private final FundamentalsAnalyst analyst;
    private final HarnessCallExecutor executor;

    public ResearchChatService(VectorStore vectorStore, FundamentalsAnalyst analyst) {
        if (vectorStore == null || analyst == null) {
            throw new IllegalArgumentException("研究编排依赖不能为空");
        }
        this.vectorStore = vectorStore;
        this.analyst = analyst;
        this.executor =
                new HarnessCallExecutor(
                        new io.github.sjcud.stockagent.runtime.harness.FailureClassifier());
    }

    /** 检索问题相关证据后生成研究回答。 */
    public ResearchChatAnswer ask(String question, MarketSnapshot snapshot) {
        return ask(question, snapshot, () -> false);
    }

    /** 带取消信号的研究请求；每个请求拥有独立预算账本。 */
    public ResearchChatAnswer ask(
            String question, MarketSnapshot snapshot, BooleanSupplier cancelled) {
        return ask(question, snapshot, cancelled, ResearchProgressListener.NONE);
    }

    /** 资料问答观察入口，检索和回答状态均由实际执行位置通知。 */
    public ResearchChatAnswer ask(
            String question,
            MarketSnapshot snapshot,
            BooleanSupplier cancelled,
            ResearchProgressListener progress) {
        progress.onProgress("RETRIEVAL", "", "STARTED");
        BudgetLedger ledger = new BudgetLedger(3, System.currentTimeMillis() + 30000L, 6000);
        List<RetrievalHit> hits;
        try {
            hits =
                    new TimedVectorStore(vectorStore)
                            .search(
                                    new RetrievalQuery(question, 5),
                                    ledger.remainingMillis(),
                                    cancelled);
        } catch (Exception exception) {
            throw new IllegalStateException("RAG 检索失败", exception);
        }
        progress.onProgress("RETRIEVAL", "", "COMPLETED");
        StringBuilder evidence = new StringBuilder();
        for (RetrievalHit hit : hits) {
            evidence.append("[片段=")
                    .append(hit.getChunkId())
                    .append("] ")
                    .append(hit.getText())
                    .append("；");
        }
        try {
            progress.onProgress("ANSWER", "", "STARTED");
            String answer =
                    executor.execute(
                            () -> {
                                long started = System.nanoTime();
                                String output =
                                        analyst.analyze(question, snapshot, evidence.toString());
                                progress.onTrace(
                                        new AgentTrace(
                                                "FUNDAMENTALS",
                                                analyst.buildPrompt(
                                                        question, snapshot, evidence.toString()),
                                                output,
                                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                                        System.nanoTime() - started)));
                                return output;
                            },
                            ledger,
                            2,
                            100L,
                            cancelled,
                            1500);
            progress.onProgress("ANSWER", "", "COMPLETED");
            return new ResearchChatAnswer(answer, hits);
        } catch (Exception exception) {
            throw new IllegalStateException("研究模型调用失败", exception);
        }
    }
}
