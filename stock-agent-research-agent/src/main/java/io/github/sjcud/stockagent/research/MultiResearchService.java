package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.data.StockResearchData;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.*;
import io.github.sjcud.stockagent.runtime.harness.*;

import java.util.Collections;
import java.util.List;

/** RAG → 五个独立分析师 → 组合经理，所有模型调用共用一次请求预算。 */
public class MultiResearchService {
    /** 统一处理多角色共享上下文，避免各角色各自截断造成证据不一致。 */
    private final ContextPolicy contextPolicy = new ContextPolicy();
    /** 与向量数据库供应商无关的检索接口。 */
    private final VectorStore store;

    /** 按实际引擎枚举注册工作流策略，新增实现不侵入取数和上下文处理。 */
    private final java.util.Map<ResearchEngine, ResearchWorkflow> workflows;

    /** 请求调用次数上限，包含组合经理。 */
    private final int modelCalls;

    /** 注入检索、角色策略和组合经理。 */
    public MultiResearchService(
            VectorStore store, List<ResearchAnalyst> analysts, PortfolioManager manager) {
        if (store == null || manager == null) {
            throw new IllegalArgumentException("依赖不能为空");
        }
        this.store = store;
        java.util.Map<ResearchEngine, ResearchWorkflow> registry = new java.util.EnumMap<ResearchEngine, ResearchWorkflow>(ResearchEngine.class);
        registry.put(ResearchEngine.JAVA_EXECUTOR, new ExecutorResearchWorkflow(analysts, manager));
        registry.put(ResearchEngine.ALIBABA_GRAPH, new GraphResearchWorkflow(analysts, manager));
        this.workflows = java.util.Collections.unmodifiableMap(registry);
        this.modelCalls = analysts.size() + 1;
    }

    /** 执行一次完整研究；任一角色失败即终止，不汇总不完整报告。 */
    public MultiResearchAnswer ask(String question, MarketSnapshot snapshot, ResearchMode mode)
            throws Exception {
        return execute(question, snapshot, mode, "", true, ResearchProgressListener.NONE);
    }

    /** 显式选择引擎的同步对照入口。 */
    public MultiResearchAnswer ask(String question, MarketSnapshot snapshot, ResearchMode mode, ResearchEngine engine) throws Exception {
        return execute(question, snapshot, mode, "", true, ResearchProgressListener.NONE, "", engine);
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
        return ask(question, data, mode, progress, history, ResearchEngine.JAVA_EXECUTOR);
    }

    /** 工作台显式引擎入口，取数与上下文处理在两种引擎之间完全共用。 */
    public MultiResearchAnswer ask(String question, StockResearchData data, ResearchMode mode,
            ResearchProgressListener progress, String history, ResearchEngine engine) throws Exception {
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
                history, engine);
    }

    /** 服务端归档驱动的局部重跑：固定原问题/行情/证据，不重新取数或检索。 */
    public MultiResearchAnswer rerun(String question, MarketSnapshot snapshot, String evidence, List<AnalystResult> previous,
            java.util.Set<AnalystRole> selected, ResearchMode mode, ResearchProgressListener progress) throws Exception {
        if (selected == null) throw new IllegalArgumentException("重跑角色不能为空");
        BudgetLedger ledger = new BudgetLedger(selected.size() + 1, System.currentTimeMillis() + 120000L, 60000);
        WorkflowResult result = ((GraphResearchWorkflow) workflows.get(ResearchEngine.ALIBABA_GRAPH))
                .executeSelected(question, snapshot, evidence, mode, ledger, progress, previous, selected);
        return new MultiResearchAnswer(mode, result.getDecision(), java.util.Collections.emptyList(), null, ResearchEngine.ALIBABA_GRAPH, result.getState());
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
        return execute(question, snapshot, mode, marketEvidence, includeRag, progress, history, ResearchEngine.JAVA_EXECUTOR);
    }

    /** 引擎选择只作用于角色/经理调度，不改变共享证据与预算配置。 */
    private MultiResearchAnswer execute(String question, MarketSnapshot snapshot, ResearchMode mode,
            String marketEvidence, boolean includeRag, ResearchProgressListener progress, String history, ResearchEngine engine) throws Exception {
        if (question == null || question.trim().isEmpty() || snapshot == null || mode == null || engine == null || progress == null) {
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
        WorkflowResult result = workflows.get(engine).execute(question, snapshot, compactEvidence, mode, ledger, progress);
        return new MultiResearchAnswer(mode, result.getDecision(), hits, prepared.getReport(), engine, result.getState());
    }
}
