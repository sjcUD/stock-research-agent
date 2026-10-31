package io.github.sjcud.stockagent.analyst;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.llm.ChatModelGateway;

/**
 * 基本面分析角色的最小实现。
 *
 * <p>工具负责提供结构化行情事实，模型负责解释事实。类本身不计算价格，
 * 也不直接读取 provider；后续增加财报工具时仍通过同样的 prompt 组合方式
 * 交给 Agent/Graph 编排。</p>
 */
public class FundamentalsAnalyst {
    private final ChatModelGateway chatModel;

    public FundamentalsAnalyst(ChatModelGateway chatModel) {
        if (chatModel == null) {
            throw new IllegalArgumentException("chatModel 不能为空");
        }
        this.chatModel = chatModel;
    }

    /** 将结构化工具事实放入模型上下文并返回分析报告。 */
    public String analyze(MarketSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot 不能为空");
        }
        String prompt = "请基于以下结构化工具事实进行基本面分析。"
                + "证券=" + snapshot.getSymbol()
                + "，收盘价=" + snapshot.getClose()
                + "，数据日期=" + snapshot.getAsOf()
                + "，来源=" + snapshot.getSource()
                + "。如果缺少财报数据，请明确说明。";
        return chatModel.chat(prompt);
    }
}
