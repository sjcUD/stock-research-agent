package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import java.util.*;
import io.github.sjcud.stockagent.runtime.harness.ContextPolicy;

/** 多角色研究响应，保留来源引用与六个角色的执行过程。 */
public final class MultiResearchAnswer {
    /** 采用的调度模式。 */
    private final ResearchMode mode;
    /** 组合经理汇总及原始报告。 */
    private final PortfolioDecision decision;
    /** 本次检索所得来源，供前端引用抽屉展示。 */
    private final List<RetrievalHit> evidence;
    /** 本次运行的角色输入输出，仅在响应中保存。 */
    private final List<AgentTrace> traces;
    private final ContextPolicy.Report contextReport;
    /** 本轮实际使用的编排引擎。 */
    private final ResearchEngine engine;
    /** 图终态及预算摘要；Java引擎仅提供预算快照。 */
    private final Map<String, Object> workflowState;
    /** 返回实际引擎，不能根据mode猜测。 */
    public ResearchEngine getEngine() { return engine; }
    /** 返回图状态键和已完成角色，正文仍通过原报告和trace接口查看。 */
    public Map<String, Object> getWorkflowState() { return workflowState; }
    /** 创建不可变响应，避免前端适配层修改共享列表。 */
    public MultiResearchAnswer(ResearchMode mode, PortfolioDecision decision, List<RetrievalHit> evidence) {
        this(mode, decision, evidence, null);
    }

    /** 创建带上下文裁剪诊断的响应。 */
    public MultiResearchAnswer(ResearchMode mode, PortfolioDecision decision, List<RetrievalHit> evidence,
            ContextPolicy.Report contextReport) {
        this(mode, decision, evidence, contextReport, ResearchEngine.JAVA_EXECUTOR, Collections.<String, Object>emptyMap());
    }

    /** 返回引擎和状态摘要，同时保持旧构造函数兼容。 */
    public MultiResearchAnswer(ResearchMode mode, PortfolioDecision decision, List<RetrievalHit> evidence,
            ContextPolicy.Report contextReport, ResearchEngine engine, Map<String, Object> workflowState) {
        this.engine = engine;
        this.workflowState = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(workflowState));
        this.mode = mode;
        this.decision = decision;
        this.evidence = Collections.unmodifiableList(new ArrayList<RetrievalHit>(evidence));
        List<AgentTrace> collected = new ArrayList<AgentTrace>();
        for (AnalystResult result : decision.getAnalystResults()) {
            if (result.getTrace() != null) { collected.add(result.getTrace()); }
        }
        if (decision.getTrace() != null) { collected.add(decision.getTrace()); }
        this.traces = Collections.unmodifiableList(collected);
        this.contextReport = contextReport;
    }
    /** 返回调度模式。 */
    public ResearchMode getMode() { return mode; }
    /** 返回研究决策。 */
    public PortfolioDecision getDecision() { return decision; }
    /** 返回带 Citation 的检索证据。 */
    public List<RetrievalHit> getEvidence() { return evidence; }
    /** 返回本次运行的执行轨迹。 */
    public List<AgentTrace> getTraces() { return traces; }
    /** 返回本轮上下文裁剪诊断。 */
    public ContextPolicy.Report getContextReport() { return contextReport; }
}
