package io.github.sjcud.stockagent.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.llm.ChatModelGateway;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证多分析师串行、并行和组合汇总契约。 */
public class MultiAnalystOrchestratorTest {

    @Test
    public void shouldRejectDuplicateRoles() {
        ResearchAnalyst analyst = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> "结果");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MultiAnalystOrchestrator(Arrays.asList(analyst, analyst)));
    }

    @Test
    public void shouldRunSerialAndParallelWithStableOrder() throws Exception {
        ChatModelGateway model = new ChatModelGateway() {
            @Override public String chat(String prompt) { return prompt.substring(0, 2); }
        };
        List<ResearchAnalyst> analysts = Arrays.<ResearchAnalyst>asList(
                new PromptResearchAnalyst(AnalystRole.RISK, "风险分析", model),
                new PromptResearchAnalyst(AnalystRole.TECHNICAL, "技术分析", model));
        MultiAnalystOrchestrator orchestrator = new MultiAnalystOrchestrator(analysts);
        MarketSnapshot snapshot = new MarketSnapshot("600519", "1680", "2026-10-30", "SAMPLE");

        assertEquals(AnalystRole.RISK, orchestrator.runSerial("问题", snapshot, "证据").get(0).getRole());
        assertEquals(AnalystRole.RISK, orchestrator.runParallel("问题", snapshot, "证据").get(0).getRole());
    }
}
