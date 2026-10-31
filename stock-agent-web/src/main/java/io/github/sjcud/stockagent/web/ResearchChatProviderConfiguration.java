package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.research.ResearchChatService;
import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 当 ChatModel 和 Qdrant 都就绪时装配聊天研究服务。 */
@Configuration
@ConditionalOnBean({ChatModelGateway.class, VectorStore.class})
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
}
