package io.github.sjcud.stockagent.llm;

/** 模型运行观测接口；LLM只发事件，由装配层决定是否接入预算，不反向依赖Harness。 */
public interface ModelUsageListener {
    /** 发送provider请求前通知，用于检查一次预留只对应一次请求。 */
    default void beforeCall() { }
    /** provider上报输入/输出Token，缺失为null，不能推断成零。 */
    void onUsage(Integer inputTokens, Integer outputTokens);
}
