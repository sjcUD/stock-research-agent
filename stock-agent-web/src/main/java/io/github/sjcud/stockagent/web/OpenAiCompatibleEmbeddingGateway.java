package io.github.sjcud.stockagent.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjcud.stockagent.llm.EmbeddingGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 Embedding HTTP 网关。
 *
 * <p>直接保留兼容服务的路径前缀，适配 DashScope 等
 * {@code /compatible-mode/v1} 服务；核心层仍只依赖 EmbeddingGateway。</p>
 */
public class OpenAiCompatibleEmbeddingGateway implements EmbeddingGateway {

    private final RestClient client;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Integer dimensions;

    public OpenAiCompatibleEmbeddingGateway(String baseUrl, String apiKey, String model, Integer dimensions) {
        this.client = RestClient.builder().build();
        this.endpoint = buildEndpoint(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    /** 批量调用 embeddings 接口并按服务返回顺序转换向量。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        Map<String, Object> request = new HashMap<String, Object>();
        request.put("model", model);
        request.put("input", texts);
        if (dimensions != null && dimensions.intValue() > 0) {
            request.put("dimensions", dimensions);
        }
        JsonNode response = client.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        return readVectors(response);
    }

    private List<float[]> readVectors(JsonNode response) {
        if (response == null || !response.has("data")) {
            throw new IllegalStateException("Embedding 响应缺少 data");
        }
        List<float[]> vectors = new ArrayList<float[]>();
        for (JsonNode item : response.get("data")) {
            JsonNode values = item.get("embedding");
            if (values == null || !values.isArray()) {
                throw new IllegalStateException("Embedding 响应缺少 embedding");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = values.get(i).floatValue();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private String buildEndpoint(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.endsWith("/embeddings") ? value
                : (value.endsWith("/v1") ? value + "/embeddings" : value + "/v1/embeddings");
    }
}
