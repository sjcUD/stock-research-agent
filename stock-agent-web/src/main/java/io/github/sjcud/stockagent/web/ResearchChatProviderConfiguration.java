package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.research.ResearchChatService;
import io.github.sjcud.stockagent.research.MultiResearchService;
import io.github.sjcud.stockagent.research.DefaultAnalystFactory;
import io.github.sjcud.stockagent.research.PortfolioManager;
import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当 ChatModel 和 Qdrant 都就绪时装配聊天研究服务。 */
@Configuration
@ConditionalOnProperty(name = "stock.agent.qdrant.enabled", havingValue = "true")
public class ResearchChatProviderConfiguration {

    /** 创建基本面分析师。 */
    @Bean
    public FundamentalsAnalyst fundamentalsAnalyst(ChatModelGateway chatModelGateway) {
        return new FundamentalsAnalyst(chatModelGateway);
    }

    /** 创建带 RAG 证据的聊天研究服务。 */
    @Bean
    public ResearchChatService researchChatService(VectorStore vectorStore,
                                                   FundamentalsAnalyst analyst) {
        return new ResearchChatService(vectorStore, analyst);
    }
    /** 创建五角色分析与组合经理汇总服务，保留单分析师入口用于学习比较。 */
    @Bean
    public MultiResearchService multiResearchService(VectorStore vectorStore, ChatModelGateway gateway) {
        return new MultiResearchService(vectorStore, DefaultAnalystFactory.create(gateway), new PortfolioManager(gateway));
    }
}
