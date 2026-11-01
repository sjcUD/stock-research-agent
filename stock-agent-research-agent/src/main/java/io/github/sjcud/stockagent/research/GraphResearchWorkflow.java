package io.github.sjcud.stockagent.research;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
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
        Set<AnalystRole> selected = EnumSet.noneOf(AnalystRole.class);
        for (ResearchAnalyst analyst : analysts) selected.add(analyst.getRole());
        return executeSelected(question, snapshot, evidence, mode, budget, progress, Collections.<AnalystResult>emptyList(), selected);
    }

    /** 复用同一事实快照中的完整旧报告；只执行选中角色，空集合表示只重跑经理。 */
    public WorkflowResult executeSelected(String question, MarketSnapshot snapshot, String evidence, ResearchMode mode,
            BudgetLedger budget, ResearchProgressListener progress, List<AnalystResult> previousReports, Set<AnalystRole> selectedRoles) throws Exception {
        if (previousReports == null || selectedRoles == null) throw new IllegalArgumentException("重跑计划不能为空");
        final Set<AnalystRole> selected = EnumSet.noneOf(AnalystRole.class); selected.addAll(selectedRoles);
        final Map<String, Object> initial = ResearchGraphState.initial(question, snapshot, evidence);
        Map<AnalystRole, AnalystResult> previous = new EnumMap<AnalystRole, AnalystResult>(AnalystRole.class);
        Set<AnalystRole> registered = EnumSet.noneOf(AnalystRole.class); for (ResearchAnalyst analyst : analysts) registered.add(analyst.getRole());
        if (!registered.containsAll(selected)) throw new IllegalArgumentException("选中角色未注册");
        for (AnalystResult report : previousReports) {
            if (report == null || !registered.contains(report.getRole()) || previous.put(report.getRole(), report) != null || !evidence.equals(report.getEvidence()))
                throw new IllegalArgumentException("旧报告角色或证据快照不一致");
        }
        List<String> reused = new ArrayList<String>(), executed = new ArrayList<String>();
        for (ResearchAnalyst analyst : analysts) {
            if (selected.contains(analyst.getRole())) executed.add(analyst.getRole().name());
            else {
                AnalystResult report = previous.get(analyst.getRole());
                if (report == null) throw new IllegalArgumentException("缺少未选角色的旧报告");
                initial.put(ResearchGraphState.reportKey(analyst.getRole()), ResearchGraphState.encode(report)); reused.add(analyst.getRole().name());
            }
        }
        initial.put("selectedRoles", executed);
        if (question == null || question.trim().isEmpty() || snapshot == null || evidence == null || mode == null || budget == null || progress == null)
            throw new IllegalArgumentException("图研究输入无效");
        final Thread caller = Thread.currentThread();
        final AtomicBoolean stopped = new AtomicBoolean();
        final AtomicReference<Exception> firstFailure = new AtomicReference<Exception>();
        final BooleanSupplier cancelled = () -> stopped.get() || caller.isInterrupted() || Thread.currentThread().isInterrupted();
        final BudgetLedger analystBudget = WorkflowSupport.analysts(budget, selected.size());
        ExecutorService parallel = Executors.newFixedThreadPool(Math.max(1, Math.min(8, selected.size())));
        try {
            Map<String, KeyStrategy> strategies = new HashMap<String, KeyStrategy>();
            for (String key : Arrays.asList("question", "evidence", "market", "prepared", "decision", "managerTrace", "selectedRoles", "routeTaken", "dispatched")) strategies.put(key, new ReplaceStrategy());
            for (ResearchAnalyst analyst : analysts) strategies.put(ResearchGraphState.reportKey(analyst.getRole()), new ReplaceStrategy());
            StateGraph graph = new StateGraph("stock-research", () -> new HashMap<String, KeyStrategy>(strategies));
            graph.addNode("prepare", AsyncNodeAction.node_async(state -> {
                ResearchGraphState.text(state.data(), "question"); ResearchGraphState.market(state);
                check(cancelled, budget); progress.onProgress("GRAPH", "PREPARE", "COMPLETED");
                Map<String, Object> update = new LinkedHashMap<String, Object>(); update.put("prepared", true);
                update.put("routeTaken", selected.isEmpty() ? "REUSE_ONLY" : "RUN_SELECTED");
                for (String role : reused) progress.onProgress("REUSE", role, "COMPLETED");
                return update;
            }));
            List<String> nodes = new ArrayList<String>();
            for (ResearchAnalyst analyst : analysts) {
                if (!selected.contains(analyst.getRole())) continue;
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
            graph.addNode("dispatch", AsyncNodeAction.node_async(state -> Collections.<String, Object>singletonMap("dispatched", true)));
            Map<String, String> routes = new LinkedHashMap<String, String>(); routes.put("RUN_SELECTED", "dispatch"); routes.put("REUSE_ONLY", "manager");
            graph.addConditionalEdges("prepare", AsyncEdgeAction.edge_async(state -> ResearchGraphState.text(state.data(), "routeTaken")), routes);
            if (nodes.isEmpty()) graph.addEdge("dispatch", "manager");
            else if (mode == ResearchMode.PARALLEL) {
                graph.addEdge("dispatch", nodes); graph.addEdge(nodes, "manager");
            } else {
                String previousNode = "dispatch";
                for (String node : nodes) { graph.addEdge(previousNode, node); previousNode = node; }
                graph.addEdge(previousNode, "manager");
            }
            graph.addEdge("manager", StateGraph.END);
            CompiledGraph compiled = graph.compile(); compiled.setMaxIterations(analysts.size() + 8);
            RunnableConfig config = RunnableConfig.builder().defaultParallelExecutor(parallel).defaultParallelAggregationStrategy(NodeAggregationStrategy.ALL_OF).build();
            Optional<OverAllState> finalState = new DeadlineExecutor().call(() -> compiled.invoke(initial, config), budget, cancelled);
            if (!finalState.isPresent()) throw new IllegalStateException("图未生成终态");
            check(cancelled, budget); OverAllState state = finalState.get();
            List<AnalystResult> reports = ResearchGraphState.reports(state, analysts);
            PortfolioDecision decision = new PortfolioDecision(ResearchGraphState.text(state.data(), "decision"), reports, ResearchGraphState.trace(state.data().get("managerTrace")));
            Map<String, Object> summary = new LinkedHashMap<String, Object>();
            List<String> completed = new ArrayList<String>(); for (AnalystResult report : reports) completed.add(report.getRole().name());
            summary.put("completedRoles", Collections.unmodifiableList(completed)); summary.put("decisionProduced", true);
            summary.put("availableKeys", Collections.unmodifiableList(new ArrayList<String>(new TreeSet<String>(state.data().keySet()))));
            summary.put("executedRoles", Collections.unmodifiableList(new ArrayList<String>(executed)));
            summary.put("reusedRoles", Collections.unmodifiableList(new ArrayList<String>(reused)));
            summary.put("routeTaken", ResearchGraphState.text(state.data(), "routeTaken"));
            summary.put("budget", budget.snapshot());
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
