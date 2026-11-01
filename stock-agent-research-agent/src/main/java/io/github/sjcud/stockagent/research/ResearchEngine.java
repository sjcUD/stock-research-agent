package io.github.sjcud.stockagent.research;

/** 引擎与SERIAL/PARALLEL调度方式正交，旧请求继续使用Java引擎。 */
public enum ResearchEngine {
    /** Java执行器对照基线。 */
    JAVA_EXECUTOR,
    /** Spring AI Alibaba 1.1.2.2显式StateGraph。 */
    ALIBABA_GRAPH
}
