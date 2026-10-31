package io.github.sjcud.stockagent.llm;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证模型 profile 的必填字段和 Embedding 维度约束。 */
public class ModelProfileTest {

    @Test
    public void shouldRejectEmbeddingProfileWithoutPositiveDimension() {
        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                ModelProfile.embedding("dashscope", "text-embedding-v3", 0, 10);
            }
        });
    }

    @Test
    public void shouldKeepProviderAndBatchConfiguration() {
        ModelProfile profile = ModelProfile.embedding("qwen", "text-embedding-v3", 1024, 10);

        assertEquals("qwen", profile.getProvider());
        assertEquals(1024, profile.getDimensions());
        assertEquals(10, profile.getBatchSize());
    }
}
