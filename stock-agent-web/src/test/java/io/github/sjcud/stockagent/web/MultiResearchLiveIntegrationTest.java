package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.research.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 使用实际 Spring 装配验证五角色到组合经理的真实模型链路；默认跳过。 */
public class MultiResearchLiveIntegrationTest {
    @Test
    public void shouldReturnReportsCitationsAndTracesThroughRealProviders() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MULTI_RESEARCH_INTEGRATION")));
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ChatModelProviderConfiguration.class, EmbeddingProviderConfiguration.class,
                    QdrantProviderConfiguration.class, ResearchChatProviderConfiguration.class);
            context.refresh();
            MultiResearchAnswer answer = context.getBean(MultiResearchService.class).ask(
                    "这是SAMPLE样例研究，150字以内说明证据支持的内容及缺失数据，不当作真实行情。",
                    new MarketSnapshot("600519", "1680", "2026-10-30", "SAMPLE"), ResearchMode.PARALLEL);
            assertEquals(5, answer.getDecision().getAnalystResults().size());
            assertEquals(6, answer.getTraces().size());
            assertFalse(answer.getDecision().getDecision().trim().isEmpty());
            for (AgentTrace trace : answer.getTraces()) {
                assertFalse(trace.getInput().isEmpty());
                assertFalse(trace.getOutput().trim().isEmpty());
            }
            // 空集合可用于空库测试；存在检索结果时每条都应携带可追溯引用。
            answer.getEvidence().forEach(hit -> assertNotNull(hit.getCitation()));
        }
    }
}
