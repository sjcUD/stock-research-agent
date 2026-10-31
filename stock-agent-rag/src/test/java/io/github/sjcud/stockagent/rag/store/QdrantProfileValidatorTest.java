package io.github.sjcud.stockagent.rag.store;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证 Qdrant collection 不能接收不同 Embedding 维度。 */
public class QdrantProfileValidatorTest {

    @Test
    public void shouldRejectDimensionMismatch() {
        QdrantProfileValidator validator = new QdrantProfileValidator(1024, "dashscope:text-embedding-v3");

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                validator.validate(1536, "openai:text-embedding-3-large");
            }
        });
    }
}
