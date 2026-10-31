package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 默认角色工厂；可通过替换 ResearchAnalyst 实现接入工具型分析师。 */
public final class DefaultAnalystFactory {
    /** 工具类无需实例化。 */
    private DefaultAnalystFactory() { }

    /** 按基本面、技术面、新闻、情绪、风险注册五个独立角色。 */
    public static List<ResearchAnalyst> create(ChatModelGateway model) {
        List<ResearchAnalyst> analysts = new ArrayList<ResearchAnalyst>();
        analysts.add(new PromptResearchAnalyst(AnalystRole.FUNDAMENTALS,
                "你是基本面分析师。分析收入、利润、估值和财务质量；没有财报时列出缺失信息。", model));
        analysts.add(new PromptResearchAnalyst(AnalystRole.TECHNICAL,
                "你是技术面分析师。分析趋势、成交量和技术指标；单个收盘价不足以计算 MACD、RSI，禁止自行补全。", model));
        analysts.add(new PromptResearchAnalyst(AnalystRole.NEWS,
                "你是新闻分析师。分析事件时间、来源和影响路径；区分已发生事件与预测，缺少新闻时停止事件推断。", model));
        analysts.add(new PromptResearchAnalyst(AnalystRole.SENTIMENT,
                "你是情绪分析师。分析社交讨论的样本、时间和偏差；没有社交样本不能声称已测得市场情绪。", model));
        analysts.add(new PromptResearchAnalyst(AnalystRole.RISK,
                "你是风险分析师。检查数据时效、来源可靠性、流动性和下行情景；列出风险约束及尚未验证的条件。", model));
        return Collections.unmodifiableList(analysts);
    }
}
