package io.github.sjcud.stockagent.data;

import java.time.LocalDate;
import java.util.*;

/** 固定单标的教学样例，SAMPLE 不冒充在线行情或完整真实交易日历。 */
public class SampleStockProvider implements StockDataProvider {
    /** 固定样例截止日期。 */
    public static final String LAST_SAMPLE_DATE = "2026-10-30";
    /** 返回固定快照，只接受有样例覆盖的标的和日期。 */
    public MarketSnapshot snapshot(String symbol, String asOf) { return load(symbol, asOf).getSnapshot(); }
    /** 加载固定合成日线与资讯，所有来源均明确标记 SAMPLE。 */
    @Override public StockResearchData load(String symbol, String asOf) {
        if (!"600519".equals(LiveStockDataProvider.normalizeSymbol(symbol)) || !LAST_SAMPLE_DATE.equals(asOf)) {
            throw new IllegalArgumentException("SAMPLE 只覆盖 600519 / " + LAST_SAMPLE_DATE);
        }
        List<DailyBar> bars = new ArrayList<DailyBar>();
        LocalDate date = LocalDate.parse(LAST_SAMPLE_DATE).minusDays(89);
        for (int index = 0; index < 90; index++) {
            String close = Integer.toString(1591 + index);
            bars.add(new DailyBar(date.plusDays(index).toString(),close,close,Integer.toString(1592+index),Integer.toString(1590+index)));
        }
        DataFeed<DailyBar> history = new DataFeed<DailyBar>("SAMPLE", "sample-v1", "2026-10-30T00:00:00Z",
                DataStatus.AVAILABLE,false,"SYNTHETIC_CALENDAR_DAYS;NOT_REAL_TRADES;CNY;UNADJUSTED",bars);
        MarketArticle article = new MarketArticle("sample-news", "教学样例：财务披露事件", "纯合成内容，不代表公司实际公告或新闻。",
                LAST_SAMPLE_DATE,"","SAMPLE");
        DataFeed<MarketArticle> news = new DataFeed<MarketArticle>("SAMPLE","sample-v1","2026-10-30T00:00:00Z",
                DataStatus.AVAILABLE,true,"SYNTHETIC",Collections.singletonList(article));
        DataFeed<MarketArticle> announcements = new DataFeed<MarketArticle>("SAMPLE","sample-v1","2026-10-30T00:00:00Z",
                DataStatus.EMPTY,true,"NO_SAMPLE_ANNOUNCEMENTS",Collections.<MarketArticle>emptyList());
        return new StockResearchData(asOf,DataMode.SAMPLE,new MarketSnapshot("600519","1680.00",LAST_SAMPLE_DATE,"SAMPLE"),
                history,news,announcements,TechnicalIndicators.calculate(bars));
    }
}
