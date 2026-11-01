package io.github.sjcud.stockagent.runtime.harness;

/** 保护内容超限时拒绝调用，不能为满足长度而截断本轮问题或财务事实。 */
public final class ContextLimitException extends IllegalArgumentException {
    /** 失败也保留预算比较元数据，不包含原文。 */
    private final ContextPolicy.Report report;
    /** 创建可诊断的安全错误。 */
    public ContextLimitException(ContextPolicy.Report report){super("本轮问题与关键事实已超过上下文额度，请缩短问题或调整上限。");this.report=report;}
    /** 返回拒绝原因对应的估算报告。 */
    public ContextPolicy.Report getReport(){return report;}
}
