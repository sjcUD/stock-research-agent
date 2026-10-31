package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.EmbeddingGateway;
import java.net.URI;
import java.net.URISyntaxException;
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
        OpenAiApi api = buildApi(baseUrl, apiKey);
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(model);
        options.setDimensions(dimensions);
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
    }

    /** Spring AI 默认会追加 /v1，兼容用户把 /v1 写入 base-url 的配置方式。 */
    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("/v1") ? value.substring(0, value.length() - 3) : value;
    }

    /** 保留 OpenAI 兼容服务的路径前缀，例如 DashScope 的 /compatible-mode。 */
    private OpenAiApi buildApi(String baseUrl, String apiKey) {
        String normalized = normalizeBaseUrl(baseUrl);
        try {
            URI uri = new URI(normalized);
            String prefix = uri.getPath() == null ? "" : uri.getPath();
            String path = prefix.endsWith("/v1") ? "embeddings" : "v1/embeddings";
            return OpenAiApi.builder().baseUrl(normalized).apiKey(apiKey)
                    .embeddingsPath(path).build();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Embedding base-url 不合法: " + baseUrl, exception);
        }
    }

    /** 将供应商模型适配为项目内的 EmbeddingGateway。 */
    @Bean
    public EmbeddingGateway embeddingGateway(
            @Value("${stock.agent.embedding.base-url:https://api.openai.com}") String baseUrl,
            @Value("${stock.agent.embedding.api-key}") String apiKey,
            @Value("${stock.agent.embedding.model:text-embedding-3-small}") String model,
            @Value("${stock.agent.embedding.dimensions:1536}") Integer dimensions) {
        return new OpenAiCompatibleEmbeddingGateway(baseUrl, apiKey, model, dimensions);
    }
}
