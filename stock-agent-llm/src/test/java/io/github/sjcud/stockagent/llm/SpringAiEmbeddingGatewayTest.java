package io.github.sjcud.stockagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** 验证 Spring AI EmbeddingModel 到项目网关的适配。 */
public class SpringAiEmbeddingGatewayTest {

    @Test
    public void shouldDelegateBatchEmbedding() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return null;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return Collections.singletonList(new float[] {1.0F, 2.0F});
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return new float[] {1.0F, 2.0F};
            }
        };

        List<float[]> result = new SpringAiEmbeddingGateway(model).embed(Arrays.asList("收入"));

        assertEquals(2, result.get(0).length);
    }
}
