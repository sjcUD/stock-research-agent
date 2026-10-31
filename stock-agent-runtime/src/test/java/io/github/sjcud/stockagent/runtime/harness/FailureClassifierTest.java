package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证基础错误分类，避免对权限错误无限重试。 */
public class FailureClassifierTest {

    @Test
    public void shouldClassifyUnauthorizedAsPermanent() {
        assertEquals(FailureClass.PERMANENT, new FailureClassifier().classify(401));
    }
}
