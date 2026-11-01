package io.github.sjcud.stockagent.data;

/** 日线价格复权口径；未复权用于还原供应商原始成交价。 */
public enum PriceAdjustmentMode {
    /** 供应商原始价格，不应用公司行为因子。 */
    UNADJUSTED,
    /** 前复权：以当前附近价格为基准，便于连续走势分析。 */
    FORWARD_ADJUSTED,
    /** 后复权：以历史基准价格为基准，便于长期收益比较。 */
    BACKWARD_ADJUSTED
}
