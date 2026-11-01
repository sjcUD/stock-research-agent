package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.data.StockResearchData;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.*;
import io.github.sjcud.stockagent.runtime.harness.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** RAG → 五个独立分析师 → 组合经理，所有模型调用共用一次请求预算。 */
public class MultiResearchService {
    /** 统一处理多角色共享上下文，避免各角色各自截断造成证据不一致。 */
    private final ContextPolicy contextPolicy = new ContextPolicy();
    /** 与向量数据库供应商无关的检索接口。 */
    private final VectorStore store;

    /** 负责角色调度的编排器。 */
    private final MultiAnalystOrchestrator orchestrator;

    /** 汇总角色。 */
    private final PortfolioManager manager;

    /** 请求调用次数上限，包含组合经理。 */
    private final int modelCalls;

    /** 注入检索、角色策略和组合经理。 */
    public MultiResearchService(
            VectorStore store, List<ResearchAnalyst> analysts, PortfolioManager manager) {
        if (store == null || manager == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.store = store;
        this.orchestrator = new MultiAnalystOrchestrator(analysts);
        this.manager = manager;
        this.modelCalls = analysts.size() + 1;
    }

    /** 执行一次完整研究；任一角色失败即终止，不汇总不完整报告。 */
    public MultiResearchAnswer ask(String question, MarketSnapshot snapshot, ResearchMode mode)
            throws Exception {
        return execute(question, snapshot, mode, "", true, ResearchProgressListener.NONE);
    }

    /** 托管市场研究使用可追溯结构化事实，暂不混入没有历史时点元数据的通用 RAG 库。 */
    public MultiResearchAnswer ask(String question, StockResearchData data, ResearchMode mode)
            throws Exception {
        return ask(question, data, mode, ResearchProgressListener.NONE);
    }

    /** 托管研究向工作台观察者回传真实阶段。 */
    public MultiResearchAnswer ask(
            String question,
            StockResearchData data,
            ResearchMode mode,
            ResearchProgressListener progress)
            throws Exception {
        return ask(question, data, mode, progress, "");
    }

    /** 带历史背景的托管研究入口。 */
    public MultiResearchAnswer ask(String question, StockResearchData data, ResearchMode mode,
            ResearchProgressListener progress, String history) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("市场数据不能为空");
        }
        return execute(
                question,
                data.getSnapshot(),
                mode,
                new MarketContextFormatter().format(data),
                false,
                progress,
                history);
    }

    /** 共用角色、预算和汇总流程；RAG 是否启用由入口策略决定。 */
    private MultiResearchAnswer execute(
            String question,
            MarketSnapshot snapshot,
            ResearchMode mode,
            String marketEvidence,
            boolean includeRag,
            ResearchProgressListener progress)
            throws Exception {
        return execute(question, snapshot, mode, marketEvidence, includeRag, progress, "");
    }

    /** 带历史背景的完整研究流程。 */
    private MultiResearchAnswer execute(
            String question, MarketSnapshot snapshot, ResearchMode mode, String marketEvidence,
            boolean includeRag, ResearchProgressListener progress, String history)
            throws Exception {
        if (question == null || question.trim().isEmpty() || snapshot == null || mode == null) {
            throw new IllegalArgumentException("问题、行情和模式不能为空");
        }
        BudgetLedger ledger =
                new BudgetLedger(modelCalls, System.currentTimeMillis() + 120000L, 60000);
        List<RetrievalHit> hits =
                includeRag
                        ? new TimedVectorStore(store)
                                .search(
                                        new RetrievalQuery(question, 5),
                                        Math.min(10000L, ledger.remainingMillis()),
                                        () -> Thread.currentThread().isInterrupted())
                        : Collections.<RetrievalHit>emptyList();
        StringBuilder evidence = new StringBuilder(marketEvidence);
        for (RetrievalHit hit : hits) {
            evidence.append("[片段=")
                    .append(hit.getChunkId())
                    .append("] ")
                    .append(hit.getText())
                    .append('\n');
        }
        List<ContextPolicy.Evidence> contextEvidence = new java.util.ArrayList<ContextPolicy.Evidence>();
        if (!marketEvidence.isEmpty()) contextEvidence.add(new ContextPolicy.Evidence("MARKET_FACTS", marketEvidence, 1.0D));
        for (RetrievalHit hit : hits) contextEvidence.add(new ContextPolicy.Evidence(hit.getChunkId(), hit.getText(), hit.getScore()));
        List<String> historyLines = new java.util.ArrayList<String>();
        if (history != null && !history.trim().isEmpty()) {
            for (String line : history.split("\\n")) if (!line.trim().isEmpty()) historyLines.add(line);
        }
        String facts = "证券=" + snapshot.getSymbol() + "；收盘=" + snapshot.getClose()
                + "；行情日=" + snapshot.getAsOf() + "；来源=" + snapshot.getSource();
        ContextPolicy.Prepared prepared = contextPolicy.compact("ANALYSTS", question, facts,
                historyLines, contextEvidence, 20000, 4000);
        String compactEvidence = prepared.getSupplement();
        // 分析师和经理各自拥有硬上限，同时通过父账本共享整次请求的全局额度。
        BudgetLedger analystBudget = ledger.child("ANALYSTS", modelCalls - 1,
                45000, Math.min(110000L, ledger.remainingMillis()));
        BudgetLedger managerBudget = ledger.child("MANAGER", 1, 15000,
                Math.min(30000L, ledger.remainingMillis()));
        List<AnalystResult> results =
                orchestrator.run(question, snapshot, compactEvidence, mode, analystBudget, progress);
        // 汇总也使用 Future 的剩余时限，避免分析师完成后经理无限等待。
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<PortfolioDecision> future = null;
        try {
            final int tokens = manager.estimateTokens(question, results);
            future =
                    pool.submit(
                            () ->
                                    new HarnessCallExecutor(new FailureClassifier())
                                            .execute(
                                                    () -> {
                                                        progress.onProgress(
                                                                "MANAGER",
                                                                "PORTFOLIO_MANAGER",
                                                                "STARTED");
                                                        PortfolioDecision decision =
                                                                manager.decide(question, results);
                                                        if (decision.getTrace() != null)
                                                            progress.onTrace(decision.getTrace());
                                                        progress.onProgress(
                                                                "MANAGER",
                                                                "PORTFOLIO_MANAGER",
                                                                "COMPLETED");
                                                        return decision;
                                                    },
                                                    managerBudget,
                                                    1,
                                                    0L,
                                                    () -> Thread.currentThread().isInterrupted(),
                                                    tokens));
            return new MultiResearchAnswer(
                    mode, future.get(Math.min(ledger.remainingMillis(), managerBudget.remainingMillis()), TimeUnit.MILLISECONDS), hits,
                    prepared.getReport());
        } finally {
            if (future != null) {
                future.cancel(true);
            }
            pool.shutdownNow();
        }
    }
}
