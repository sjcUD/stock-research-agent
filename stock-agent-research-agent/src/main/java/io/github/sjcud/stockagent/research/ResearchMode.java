package io.github.sjcud.stockagent.research;

/** 只改变执行调度；两种模式都让分析师独立读取同一份证据。 */
public enum ResearchMode {
    /** 依次执行独立角色，当前角色失败后停止。 */
    SERIAL,
    /** 同时调度独立角色，完成后汇聚。 */
    PARALLEL
}
