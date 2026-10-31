package io.github.sjcud.stockagent.research;

/** 多分析师角色枚举。 */
public enum AnalystRole {
    /** 财务质量与价值分析。 */
    FUNDAMENTALS,
    /** 价格序列与技术指标分析。 */
    TECHNICAL,
    /** 新闻事件与影响分析。 */
    NEWS,
    /** 社交样本与情绪偏差分析。 */
    SENTIMENT,
    /** 数据可靠性与风险约束分析。 */
    RISK
}
