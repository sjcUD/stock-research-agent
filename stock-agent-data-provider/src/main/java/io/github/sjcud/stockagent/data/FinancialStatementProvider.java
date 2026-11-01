package io.github.sjcud.stockagent.data;

import java.time.LocalDate;

/** 财报供应商边界；实现必须按披露日执行 point-in-time 过滤。 */
public interface FinancialStatementProvider {
    DataFeed<FinancialStatement> load(String symbol, LocalDate asOf);
}
