package io.github.sjcud.stockagent.data;

/** 数据来源模式：固定样例用于教学复现，LIVE 用于真实 provider。 */
public enum DataMode {
    /** 固定合成样例，不冒充真实市场。 */
    SAMPLE,
    /** 从当前配置的在线提供器获取数据。 */
    LIVE
}
