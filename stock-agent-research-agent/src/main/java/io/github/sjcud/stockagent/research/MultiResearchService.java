package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.*;
import io.github.sjcud.stockagent.runtime.harness.*;
import java.util.List;
import java.util.concurrent.*;

/** RAG → 五个独立分析师 → 组合经理，所有模型调用共用一次请求预算。 */
public class MultiResearchService {
    /** 与向量数据库供应商无关的检索接口。 */
    private final VectorStore store;
    /** 负责角色调度的编排器。 */
    private final MultiAnalystOrchestrator orchestrator;
    /** 汇总角色。 */
    private final PortfolioManager manager;
    /** 请求调用次数上限，包含组合经理。 */
    private final int modelCalls;
    /** 注入检索、角色策略和组合经理。 */
    public MultiResearchService(VectorStore store, List<ResearchAnalyst> analysts, PortfolioManager manager) {
        if (store == null || manager == null) { throw new IllegalArgumentException("依赖不能为空"); }
        this.store = store;
        this.orchestrator = new MultiAnalystOrchestrator(analysts);
        this.manager = manager;
        this.modelCalls = analysts.size() + 1;
    }
    /** 执行一次完整研究；任一角色失败即终止，不汇总不完整报告。 */
    public MultiResearchAnswer ask(String question, MarketSnapshot snapshot, ResearchMode mode) throws Exception {
        if (question == null || question.trim().isEmpty() || snapshot == null || mode == null) {
            throw new IllegalArgumentException("问题、行情和模式不能为空");
        }
        BudgetLedger ledger = new BudgetLedger(modelCalls, System.currentTimeMillis() + 120000L, 60000);
        List<RetrievalHit> hits = new TimedVectorStore(store).search(new RetrievalQuery(question, 5),
                Math.min(10000L, ledger.remainingMillis()), () -> Thread.currentThread().isInterrupted());
        StringBuilder evidence = new StringBuilder();
        for (RetrievalHit hit : hits) {
            evidence.append("[片段=").append(hit.getChunkId()).append("] ").append(hit.getText()).append('\n');
        }
        List<AnalystResult> results = orchestrator.run(question, snapshot, evidence.toString(), mode, ledger);
        // 汇总也使用 Future 的剩余时限，避免分析师完成后经理无限等待。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<PortfolioDecision> future = null;
        try {
            StringBuilder managerInput = new StringBuilder(question);
            for (AnalystResult result : results) {
                managerInput.append(result.getConclusion()).append(result.getEvidence());
            }
            final int tokens = MultiAnalystOrchestrator.estimateTokens(managerInput.toString(), "");
            future = pool.submit(() -> new HarnessCallExecutor(new FailureClassifier()).execute(
                    () -> manager.decide(question, results), ledger, 1, 0L,
                    () -> Thread.currentThread().isInterrupted(), tokens));
            return new MultiResearchAnswer(mode, future.get(ledger.remainingMillis(), TimeUnit.MILLISECONDS), hits);
        } finally {
            if (future != null) { future.cancel(true); }
            pool.shutdownNow();
        }
    }
}
