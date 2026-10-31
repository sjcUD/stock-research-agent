package io.github.sjcud.stockagent.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 组合经理汇总及原始角色报告，结果列表不可变。 */
public final class PortfolioDecision {
    /** 研究汇总，不触发真实交易。 */
    private final String decision;
    /** 汇总依据的角色报告。 */
    private final List<AnalystResult> analystResults;
    /** 组合经理执行快照。 */
    private final AgentTrace trace;
    /** 兼容自定义组合经理的无快照结果。 */
    public PortfolioDecision(String decision, List<AnalystResult> analystResults) {
        this(decision, analystResults, null);
    }
    /** 创建不可变的汇总结果。 */
    public PortfolioDecision(String decision, List<AnalystResult> analystResults, AgentTrace trace) {
        if (decision == null || decision.trim().isEmpty() || analystResults == null || analystResults.isEmpty()) {
            throw new IllegalArgumentException("决策和分析师结果不能为空");
        }
        this.decision = decision;
        this.analystResults = Collections.unmodifiableList(new ArrayList<AnalystResult>(analystResults));
        this.trace = trace;
    }
    /** 返回汇总文本。 */
    public String getDecision() { return decision; }
    /** 返回原始报告。 */
    public List<AnalystResult> getAnalystResults() { return analystResults; }
    /** 返回经理执行快照。 */
    public AgentTrace getTrace() { return trace; }
}
