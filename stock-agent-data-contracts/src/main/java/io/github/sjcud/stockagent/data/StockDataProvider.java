package io.github.sjcud.stockagent.data;

/** 证券数据策略边界：上层研究流程不依赖 HTTP、JSON 或具体供应商。 */
public interface StockDataProvider {
    /** 按上海时区的日终截止日加载结构化数据，日期采用 yyyy-MM-dd。 */
    StockResearchData load(String symbol, String asOf);
    /** 按指定复权口径加载，默认实现保持旧提供器只支持未复权。 */
    default StockResearchData load(String symbol, String asOf, PriceAdjustmentMode adjustment) {
        if (adjustment == null || adjustment == PriceAdjustmentMode.UNADJUSTED) return load(symbol, asOf);
        throw new UnsupportedOperationException("当前数据源不支持复权口径：" + adjustment);
    }
}
