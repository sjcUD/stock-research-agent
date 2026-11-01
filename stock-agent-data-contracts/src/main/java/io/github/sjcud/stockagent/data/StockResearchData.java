package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** 一次按研究日取数的完整结果，结构化行情与资讯分组分别保留来源。 */
public final class StockResearchData {
    /** 请求的日终截止日期。 */
    private final String requestedAsOf;
    /** SAMPLE 与 LIVE 明确区分。 */
    private final DataMode mode;
    /** 最近有效日线的快照。 */
    private final MarketSnapshot snapshot;
    /** 按请求复权口径返回的日线窗口。 */
    private final DataFeed<DailyBar> history;
    /** 最近新闻窗口，可能不完整。 */
    private final DataFeed<MarketArticle> news;
    /** 最近公告列表，可能不完整。 */
    private final DataFeed<MarketArticle> announcements;
    /** 确定性代码计算的指标，缺少足够日线时省略对应键。 */
    private final Map<String, BigDecimal> indicators;
    /** 日线价格口径，模型提示必须显式披露。 */
    private final PriceAdjustmentMode adjustmentMode;
    /** 从价格序列推导的公司行为因子，可能为空。 */
    private final List<CorporateAction> corporateActions;
    /** 按披露日过滤的财报数据，可能明确不可用。 */
    private final DataFeed<FinancialStatement> financials;
    /** 按研究日过滤的情绪快照，可能明确不可用。 */
    private final DataFeed<SentimentSnapshot> sentiment;
    /** 创建不可变的取数结果。 */
    public StockResearchData(String requestedAsOf, DataMode mode, MarketSnapshot snapshot,
            DataFeed<DailyBar> history, DataFeed<MarketArticle> news, DataFeed<MarketArticle> announcements,
            Map<String, BigDecimal> indicators) {
        this(requestedAsOf, mode, snapshot, history, news, announcements, indicators,
                PriceAdjustmentMode.UNADJUSTED, Collections.<CorporateAction>emptyList(), unavailableFinancials(), unavailableSentiment());
    }
    /** 创建包含复权口径和公司行为的完整市场结果。 */
    public StockResearchData(String requestedAsOf, DataMode mode, MarketSnapshot snapshot,
            DataFeed<DailyBar> history, DataFeed<MarketArticle> news, DataFeed<MarketArticle> announcements,
            Map<String, BigDecimal> indicators, PriceAdjustmentMode adjustmentMode, List<CorporateAction> actions) {
        this(requestedAsOf, mode, snapshot, history, news, announcements, indicators, adjustmentMode, actions, unavailableFinancials(), unavailableSentiment());
    }
    /** 创建包含财报与情绪数据的完整结果。 */
    public StockResearchData(String requestedAsOf, DataMode mode, MarketSnapshot snapshot,
            DataFeed<DailyBar> history, DataFeed<MarketArticle> news, DataFeed<MarketArticle> announcements,
            Map<String, BigDecimal> indicators, PriceAdjustmentMode adjustmentMode, List<CorporateAction> actions,
            DataFeed<FinancialStatement> financials, DataFeed<SentimentSnapshot> sentiment) {
        this.requestedAsOf = requestedAsOf; this.mode = mode; this.snapshot = snapshot; this.history = history;
        this.news = news; this.announcements = announcements; this.indicators = Collections.unmodifiableMap(new LinkedHashMap<String, BigDecimal>(indicators));
        this.adjustmentMode = adjustmentMode == null ? PriceAdjustmentMode.UNADJUSTED : adjustmentMode;
        this.corporateActions = Collections.unmodifiableList(new java.util.ArrayList<CorporateAction>(actions));
        this.financials = financials; this.sentiment = sentiment;
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
    /** 返回价格复权口径。 */
    public PriceAdjustmentMode getAdjustmentMode() { return adjustmentMode; }
    /** 返回公司行为因子列表。 */
    public List<CorporateAction> getCorporateActions() { return corporateActions; }
    public DataFeed<FinancialStatement> getFinancials() { return financials; }
    public DataFeed<SentimentSnapshot> getSentiment() { return sentiment; }
    private static DataFeed<FinancialStatement> unavailableFinancials() { return new DataFeed<FinancialStatement>("UNCONFIGURED", "", "", DataStatus.UNAVAILABLE, true, "FINANCIAL_STATEMENTS_NOT_CONFIGURED", Collections.<FinancialStatement>emptyList()); }
    private static DataFeed<SentimentSnapshot> unavailableSentiment() { return new DataFeed<SentimentSnapshot>("UNCONFIGURED", "", "", DataStatus.UNAVAILABLE, true, "SOCIAL_SENTIMENT_NOT_CONFIGURED", Collections.<SentimentSnapshot>emptyList()); }
}
