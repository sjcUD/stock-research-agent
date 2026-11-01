package io.github.sjcud.stockagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 验证 Spring AI EmbeddingModel 到项目网关的适配。 */
public class SpringAiEmbeddingGatewayTest {

    @Test
    public void shouldDelegateBatchEmbedding() {
        EmbeddingModel model =
                new EmbeddingModel() {
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

    /** 供应商批量上限必须在网关边界处理，并保留结果顺序。 */
    @Test
    public void shouldSplitLargeBatch() {
        java.util.List<Integer> sizes = new java.util.ArrayList<Integer>();
        EmbeddingModel model =
                new EmbeddingModel() {
                    public org.springframework.ai.embedding.EmbeddingResponse call(
                            org.springframework.ai.embedding.EmbeddingRequest request) {
                        return null;
                    }

                    public float[] embed(org.springframework.ai.document.Document document) {
                        throw new UnsupportedOperationException();
                    }

                    public List<float[]> embed(List<String> texts) {
                        sizes.add(texts.size());
                        java.util.List<float[]> result = new java.util.ArrayList<float[]>();
                        for (String text : texts) result.add(new float[] {Float.parseFloat(text)});
                        return result;
                    }
                };
        List<float[]> values =
                new SpringAiEmbeddingGateway(model, 2)
                        .embed(Arrays.asList("1", "2", "3", "4", "5"));
        assertEquals(Arrays.asList(2, 2, 1), sizes);
        assertEquals(5, values.size());
        assertEquals(5F, values.get(4)[0]);
    }
}
