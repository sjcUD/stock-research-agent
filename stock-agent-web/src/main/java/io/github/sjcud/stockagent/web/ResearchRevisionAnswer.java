package io.github.sjcud.stockagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjcud.stockagent.research.MultiResearchAnswer;

/** 局部重跑响应保持marketData/research形状，并明确指向未被覆盖的来源任务。 */
public final class ResearchRevisionAnswer {
    private final String sourceTaskId;
    private final JsonNode marketData;
    private final MultiResearchAnswer research;
    /** 市场来源与价格沿用原快照，不伪装成刚刚刷新。 */
    public ResearchRevisionAnswer(String sourceTaskId, JsonNode marketData, MultiResearchAnswer research) {
        this.sourceTaskId = sourceTaskId; this.marketData = marketData.deepCopy(); this.research = research;
    }
    /** 返回原任务血缘。 */
    public String getSourceTaskId() { return sourceTaskId; }
    /** 返回原市场数据及其来源/日期。 */
    public JsonNode getMarketData() { return marketData.deepCopy(); }
    /** 返回新经理与合并后的完整角色报告，executedRoles区分重跑和复用。 */
    public MultiResearchAnswer getResearch() { return research; }
}
