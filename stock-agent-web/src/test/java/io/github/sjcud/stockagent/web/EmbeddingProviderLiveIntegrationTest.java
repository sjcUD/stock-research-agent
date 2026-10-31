package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** 真实 Embedding provider 联调测试，仅通过显式环境变量启用。 */
public class EmbeddingProviderLiveIntegrationTest {

    @Test
    public void shouldReturnEmbeddingVector() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("EMBEDDING_INTEGRATION")));
        String apiKey = required("STOCK_AGENT_EMBEDDING_API_KEY");
        String baseUrl = valueOrDefault("STOCK_AGENT_EMBEDDING_BASE_URL", "https://api.openai.com");
        String modelName = valueOrDefault("STOCK_AGENT_EMBEDDING_MODEL", "text-embedding-3-small");
        int dimensions = Integer.parseInt(valueOrDefault("STOCK_AGENT_EMBEDDING_DIMENSIONS", "1536"));

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .embeddingsPath("/embeddings").build();
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(modelName);
        options.setDimensions(dimensions);
        EmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);
        List<float[]> vectors = model.embed(Collections.singletonList("A股上市公司收入增长"));

        assertEquals(1, vectors.size());
        assertTrue(vectors.get(0).length > 0);
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
        return value;
    }

    private String valueOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
