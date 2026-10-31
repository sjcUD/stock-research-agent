package io.github.sjcud.stockagent.data;

/** 证券数据策略边界：上层研究流程不依赖 HTTP、JSON 或具体供应商。 */
public interface StockDataProvider {
    /** 按上海时区的日终截止日加载结构化数据，日期采用 yyyy-MM-dd。 */
    StockResearchData load(String symbol, String asOf);
}
