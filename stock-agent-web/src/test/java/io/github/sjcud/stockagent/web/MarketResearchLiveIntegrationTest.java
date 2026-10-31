package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.research.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 可选真实市场到真实模型联调，复用实际 Spring 装配，无需提交响应资料。 */
public class MarketResearchLiveIntegrationTest {
    @Test
    public void shouldResearchRealMarketDataWithoutClientSuppliedPrice() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MARKET_RESEARCH_INTEGRATION")));
        String asOf = System.getenv("MARKET_DATA_AS_OF");
        assertNotNull(asOf,"请显式设置 MARKET_DATA_AS_OF");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ChatModelProviderConfiguration.class,EmbeddingProviderConfiguration.class,
                    QdrantProviderConfiguration.class,ResearchChatProviderConfiguration.class,MarketDataProviderConfiguration.class);
            context.refresh();
            MarketResearchAnswer answer = new MarketResearchService(context.getBean(StockDataProvider.class),
                    context.getBean(MultiResearchService.class)).ask(
                    "仅验证取数链路：每个角色用100字以内说明日期、指标和资讯覆盖限制，不给买卖建议。",
                    "600519",asOf,ResearchMode.PARALLEL);
            assertEquals(DataMode.LIVE,answer.getMarketData().getMode());
            assertEquals(5,answer.getResearch().getDecision().getAnalystResults().size());
            assertEquals(6,answer.getResearch().getTraces().size());
            assertTrue(answer.getResearch().getEvidence().isEmpty());
        }
    }
}
