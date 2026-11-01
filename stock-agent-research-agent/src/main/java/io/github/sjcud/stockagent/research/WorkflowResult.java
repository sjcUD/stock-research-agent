package io.github.sjcud.stockagent.research;

import java.util.*;

/** 编排产物：领域报告和无重复正文的状态摘要，便于API和归档保持同一结构。 */
public final class WorkflowResult {
    /** 已完成的经理决策。 */
    private final PortfolioDecision decision;
    /** 状态键、已完成角色等诊断；不包含模型、预算对象或连接句柄。 */
    private final Map<String, Object> state;
    /** 防御性复制状态诊断，具体集合由引擎构造为不可变对象。 */
    public WorkflowResult(PortfolioDecision decision, Map<String, Object> state) {
        this.decision = Objects.requireNonNull(decision); this.state = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(state));
    }
    /** 返回领域汇总。 */
    public PortfolioDecision getDecision() { return decision; }
    /** 返回可JSON归档的状态摘要。 */
    public Map<String, Object> getState() { return state; }
}
