package io.github.sjcud.stockagent.data;
import java.time.LocalDate; import java.util.Collections;
/** 未配置授权时的显式不可用实现。 */
public final class UnavailableFinancialStatementProvider implements FinancialStatementProvider {
 public DataFeed<FinancialStatement> load(String symbol, LocalDate asOf){return new DataFeed<FinancialStatement>("UNCONFIGURED","","",DataStatus.UNAVAILABLE,true,"FINANCIAL_STATEMENTS_NOT_CONFIGURED",Collections.<FinancialStatement>emptyList());}
}
