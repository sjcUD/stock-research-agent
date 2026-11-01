package io.github.sjcud.stockagent.analyst;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.llm.ChatModelGateway;

import org.junit.jupiter.api.Test;

/** 验证基本面分析器把工具事实作为模型上下文，而不是自己编造数值。 */
public class FundamentalsAnalystTest {

    @Test
    public void shouldIncludeStructuredSnapshotInModelPrompt() {
        ChatModelGateway gateway =
                new ChatModelGateway() {
                    @Override
                    public String chat(String prompt) {
                        assertTrue(prompt.contains("1680.00"));
                        assertTrue(prompt.contains("SAMPLE"));
                        return "已读取结构化行情，等待财报工具结果。";
                    }
                };
        FundamentalsAnalyst analyst = new FundamentalsAnalyst(gateway);

        String report =
                analyst.analyze(new MarketSnapshot("600519", "1680.00", "2026-10-30", "SAMPLE"));

        assertTrue(report.contains("等待财报工具结果"));
    }

    /** 引用格式是接口契约，必须显式约束，不能只要求“保留标识”。 */
    @Test
    public void shouldRequireExactCitationSyntaxAndAbstention() {
        FundamentalsAnalyst analyst =
                new FundamentalsAnalyst(
                        prompt -> {
                            assertTrue(prompt.contains("[片段=原始片段ID]"));
                            assertTrue(prompt.contains("资料不足"));
                            return "ok";
                        });
        analyst.analyze(
                "收入是多少", new MarketSnapshot("SYNTHETIC", "0", "2025-12-31", "EVALUATION"), "");
    }
}
