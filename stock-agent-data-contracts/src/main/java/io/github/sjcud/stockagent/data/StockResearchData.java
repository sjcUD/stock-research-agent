package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一次按研究日取数的完整结果，结构化行情与资讯分组分别保留来源。 */
public final class StockResearchData {
    /** 请求的日终截止日期。 */
    private final String requestedAsOf;
    /** SAMPLE 与 LIVE 明确区分。 */
    private final DataMode mode;
    /** 最近有效日线的快照。 */
    private final MarketSnapshot snapshot;
    /** 未复权日线窗口。 */
    private final DataFeed<DailyBar> history;
    /** 最近新闻窗口，可能不完整。 */
    private final DataFeed<MarketArticle> news;
    /** 最近公告列表，可能不完整。 */
    private final DataFeed<MarketArticle> announcements;
    /** 确定性代码计算的指标，缺少足够日线时省略对应键。 */
    private final Map<String, BigDecimal> indicators;
    /** 创建不可变的取数结果。 */
    public StockResearchData(String requestedAsOf, DataMode mode, MarketSnapshot snapshot,
            DataFeed<DailyBar> history, DataFeed<MarketArticle> news, DataFeed<MarketArticle> announcements,
            Map<String, BigDecimal> indicators) {
        this.requestedAsOf = requestedAsOf; this.mode = mode; this.snapshot = snapshot;
        this.history = history; this.news = news; this.announcements = announcements;
        this.indicators = Collections.unmodifiableMap(new LinkedHashMap<String, BigDecimal>(indicators));
    }
    /** 返回请求截止日。 */
    public String getRequestedAsOf() { return requestedAsOf; }
    /** 返回数据模式。 */
    public DataMode getMode() { return mode; }
    /** 返回实际行情快照。 */
    public MarketSnapshot getSnapshot() { return snapshot; }
    /** 返回日线。 */
    public DataFeed<DailyBar> getHistory() { return history; }
    /** 返回新闻。 */
    public DataFeed<MarketArticle> getNews() { return news; }
    /** 返回公告。 */
    public DataFeed<MarketArticle> getAnnouncements() { return announcements; }
    /** 返回代码计算的指标。 */
    public Map<String, BigDecimal> getIndicators() { return indicators; }
}
