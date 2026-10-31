package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.EmbeddingGateway;
import io.github.sjcud.stockagent.llm.SpringAiEmbeddingGateway;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAI 兼容 Embedding provider 配置。
 *
 * <p>只有配置 API Key 时才创建真实模型 Bean；没有密钥时应用仍可启动，
 * 便于先学习 Web、RAG 和 Harness。base-url 可切换到其他 OpenAI 兼容服务。</p>
 */
@Configuration
@ConditionalOnProperty(name = "stock.agent.embedding.api-key")
public class EmbeddingProviderConfiguration {

    /** 创建真实 Spring AI EmbeddingModel。 */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${stock.agent.embedding.api-key}") String apiKey,
            @Value("${stock.agent.embedding.base-url:https://api.openai.com}") String baseUrl,
            @Value("${stock.agent.embedding.model:text-embedding-3-small}") String model,
            @Value("${stock.agent.embedding.dimensions:1536}") Integer dimensions) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .embeddingsPath("/embeddings").build();
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);
        options.setDimensions(dimensions);
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /** 将供应商模型适配为项目内的 EmbeddingGateway。 */
    @Bean
    public EmbeddingGateway embeddingGateway(EmbeddingModel embeddingModel) {
        return new SpringAiEmbeddingGateway(embeddingModel);
    }
}
