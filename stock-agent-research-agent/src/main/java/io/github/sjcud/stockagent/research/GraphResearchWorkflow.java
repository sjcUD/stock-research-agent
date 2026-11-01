package io.github.sjcud.stockagent.research;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Spring AI Alibaba显式图：准备→独立分析节点→完整汇聚→经理→结束。
 * 每次请求创建独立图状态和可取消执行器，不共享报告，不把内存状态冒充跨进程恢复。
 */
public final class GraphResearchWorkflow implements ResearchWorkflow {
    /** 不可变角色注册表与无会话经理。 */
    private final List<ResearchAnalyst> analysts;
    private final PortfolioManager manager;
    /** 统一的模型调用预算、截止时间和取消边界。 */
    private final HarnessCallExecutor harness = new HarnessCallExecutor(new FailureClassifier());

    /** 校验角色唯一性，Graph节点名只取内部枚举，不使用用户输入。 */
    public GraphResearchWorkflow(List<ResearchAnalyst> analysts, PortfolioManager manager) {
        this.analysts = WorkflowSupport.validate(analysts); this.manager = Objects.requireNonNull(manager);
    }

    /** 运行真实StateGraph；顶层截止和共享取消旗标覆盖框架异步线程。 */
    @Override public WorkflowResult execute(String question, MarketSnapshot snapshot, String evidence, ResearchMode mode,
            BudgetLedger budget, ResearchProgressListener progress) throws Exception {
        if (question == null || question.trim().isEmpty() || snapshot == null || evidence == null || mode == null || budget == null || progress == null)
            throw new IllegalArgumentException("图研究输入无效");
        final Thread caller = Thread.currentThread();
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<Exception> firstFailure = new AtomicReference<Exception>();
        final BooleanSupplier cancelled = () -> stopped.get() || caller.isInterrupted() || Thread.currentThread().isInterrupted();
        final BudgetLedger analystBudget = WorkflowSupport.analysts(budget, analysts.size());
        ExecutorService parallel = Executors.newFixedThreadPool(Math.min(8, analysts.size()));
        try {
            Map<String, KeyStrategy> strategies = new HashMap<String, KeyStrategy>();
            for (String key : Arrays.asList("question", "evidence", "market", "prepared", "decision", "managerTrace")) strategies.put(key, new ReplaceStrategy());
            for (ResearchAnalyst analyst : analysts) strategies.put(ResearchGraphState.reportKey(analyst.getRole()), new ReplaceStrategy());
            StateGraph graph = new StateGraph("stock-research", () -> new HashMap<String, KeyStrategy>(strategies));
            graph.addNode("prepare", AsyncNodeAction.node_async(state -> {
                ResearchGraphState.text(state.data(), "question"); ResearchGraphState.market(state);
                check(cancelled, budget); progress.onProgress("GRAPH", "PREPARE", "COMPLETED");
                return Collections.<String, Object>singletonMap("prepared", true);
            }));
            List<String> nodes = new ArrayList<String>();
            for (ResearchAnalyst analyst : analysts) {
                String node = "analyst_" + analyst.getRole().name(); nodes.add(node);
                graph.addNode(node, AsyncNodeAction.node_async(state -> {
                    try {
                        String q = ResearchGraphState.text(state.data(), "question");
                        MarketSnapshot market = ResearchGraphState.market(state);
                        String context = ResearchGraphState.text(state.data(), "evidence");
                        AnalystResult report = harness.execute(() -> {
                            progress.onProgress("ANALYST", analyst.getRole().name(), "STARTED");
                            AnalystResult value = analyst.analyze(q, market, context);
                            check(cancelled, budget);
                            if (value == null || value.getRole() != analyst.getRole()) throw new IllegalStateException("分析师返回角色错配");
                            if (value.getTrace() != null) progress.onTrace(value.getTrace());
                            progress.onProgress("ANALYST", analyst.getRole().name(), "COMPLETED"); return value;
                        }, analystBudget, 1, 0, cancelled, analyst.estimateTokens(q, market, context));
                        return Collections.<String, Object>singletonMap(ResearchGraphState.reportKey(analyst.getRole()), ResearchGraphState.encode(report));
                    } catch (Exception failure) {
                        // ALL_OF等待聚合时，失败节点主动令其他Harness停止，避免等到最慢网络请求完成。
                        firstFailure.compareAndSet(null, failure); stopped.set(true); throw failure;
                    }
                }));
            }
            graph.addNode("manager", AsyncNodeAction.node_async(state -> {
                check(cancelled, budget);
                List<AnalystResult> reports = ResearchGraphState.reports(state, analysts);
                PortfolioDecision decision = WorkflowSupport.manage(ResearchGraphState.text(state.data(), "question"), reports, manager, budget, progress, cancelled);
                Map<String, Object> update = new LinkedHashMap<String, Object>(); update.put("decision", decision.getDecision());
                if (decision.getTrace() != null) update.put("managerTrace", ResearchGraphState.encodeTrace(decision.getTrace())); return update;
            }));
            graph.addEdge(StateGraph.START, "prepare");
            if (mode == ResearchMode.PARALLEL) {
                graph.addEdge("prepare", nodes); graph.addEdge(nodes, "manager");
            } else {
                String previous = "prepare";
                for (String node : nodes) { graph.addEdge(previous, node); previous = node; }
                graph.addEdge(previous, "manager");
            }
            graph.addEdge("manager", StateGraph.END);
            CompiledGraph compiled = graph.compile(); compiled.setMaxIterations(analysts.size() + 5);
            RunnableConfig config = RunnableConfig.builder().defaultParallelExecutor(parallel).defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF).build();
            Optional<OverAllState> finalState = new DeadlineExecutor().call(() -> compiled.invoke(ResearchGraphState.initial(question, snapshot, evidence), config), budget, cancelled);
            if (!finalState.isPresent()) throw new IllegalStateException("图未生成终态");
            check(cancelled, budget); OverAllState state = finalState.get();
            List<AnalystResult> reports = ResearchGraphState.reports(state, analysts);
            PortfolioDecision decision = new PortfolioDecision(ResearchGraphState.text(state.data(), "decision"), reports, ResearchGraphState.trace(state.data().get("managerTrace")));
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            List<String> completed = new ArrayList<String>(); for (AnalystResult report : reports) completed.add(report.getRole().name());
            summary.put("completedRoles", Collections.unmodifiableList(completed)); summary.put("decisionProduced", true);
            summary.put("availableKeys", Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(state.data().keySet()))));
            summary.put("mode", mode.name()); summary.put("checkpointed", false);
            return new WorkflowResult(decision, summary);
        } catch (Exception failure) {
            // 失败旗标用于取消兄弟节点，但对外仍返回首个真实错误，不能把它覆盖成“用户取消”。
            Exception original = firstFailure.get() == null ? failure : firstFailure.get();
            Throwable cause = original;
            // Reactor可能包装受检TimeoutException，按有限因果链识别，保持两种引擎HTTP语义一致。
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof HarnessTimeoutException || cause instanceof TimeoutException) {
                    TimeoutException timeout = new TimeoutException("图研究超过阶段或全局截止时间"); timeout.initCause(original); throw timeout;
                }
            }
            throw original;
        } finally { stopped.set(true); parallel.shutdownNow(); }
    }

    /** 节点发布前再次检查，禁止取消后的迟到报告和经理调用。 */
    private void check(BooleanSupplier cancelled, BudgetLedger budget) {
        if (cancelled.getAsBoolean()) throw new IllegalStateException("图研究已取消");
        if (budget.isExpired()) throw new HarnessTimeoutException();
    }
}
