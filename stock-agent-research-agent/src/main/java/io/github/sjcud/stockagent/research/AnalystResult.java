package io.github.sjcud.stockagent.research;

/** 分析师统一结果契约；证据摘要与执行快照和结论一同传递。 */
public final class AnalystResult {
    /** 分析师职责。 */
    private final AnalystRole role;
    /** 自然语言报告，不代表结构化交易指令。 */
    private final String conclusion;
    /** 此角色收到的共享证据，不宣称模型实际引用了全部证据。 */
    private final String evidence;
    /** 可选执行快照，兼容不调用模型的自定义分析师。 */
    private final AgentTrace trace;

    /** 创建没有执行快照的自定义分析结果。 */
    public AnalystResult(AnalystRole role, String conclusion, String evidence) {
        this(role, conclusion, evidence, null);
    }
    /** 创建带执行快照的分析结果。 */
    public AnalystResult(AnalystRole role, String conclusion, String evidence, AgentTrace trace) {
        if (role == null || conclusion == null || conclusion.trim().isEmpty()) {
            throw new IllegalArgumentException("分析结果字段不能为空");
        }
        this.role = role;
        this.conclusion = conclusion;
        this.evidence = evidence == null ? "" : evidence;
        this.trace = trace;
    }
    /** 返回角色。 */
    public AnalystRole getRole() { return role; }
    /** 返回报告。 */
    public String getConclusion() { return conclusion; }
    /** 返回输入证据。 */
    public String getEvidence() { return evidence; }
    /** 返回执行快照。 */
    public AgentTrace getTrace() { return trace; }
}
