package io.github.sjcud.stockagent.runtime.harness;

/** 明确区分预算终止与供应商故障，不自动重试。 */
public final class BudgetExceededException extends IllegalStateException {
    /** 固定安全文案，不包含模型响应或输入内容。 */
    public BudgetExceededException(){super("本次研究达到阶段或全局调用/估算Token预算，请调整预算后再决定是否重试。");}
}
