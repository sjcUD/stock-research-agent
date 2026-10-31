package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.StockResearchData;

/** 托管市场研究响应：原始市场证据和多角色回答一起返回。 */
public final class MarketResearchAnswer {
    /** 含真实日期、来源、版本、资讯 URL 和失败状态的数据。 */
    private final StockResearchData marketData;
    /** 分析师报告、组合经理与执行轨迹。 */
    private final MultiResearchAnswer research;
    /** 组合取数结果与研究结果。 */
    public MarketResearchAnswer(StockResearchData marketData, MultiResearchAnswer research) {
        this.marketData = marketData; this.research = research;
    }
    /** 返回可追溯市场数据。 */
    public StockResearchData getMarketData() { return marketData; }
    /** 返回研究结论和轨迹。 */
    public MultiResearchAnswer getResearch() { return research; }
}
