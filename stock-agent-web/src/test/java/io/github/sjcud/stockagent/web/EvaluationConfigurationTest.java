package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 真实评测的配置和来源验收条件也需要无外部服务的反例回归。 */
public class EvaluationConfigurationTest {
    @Test
    public void shouldAllowExplicitOverridesWithoutProductionDefaults() {
        Map<String, String> values = Collections.singletonMap("EVAL_MODEL", "model-v4");
        assertEquals(
                "model-v4",
                EvaluationLiveIntegrationTest.setting(values, "EVAL_MODEL", "PRODUCTION_MODEL"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EvaluationLiveIntegrationTest.setting(
                                Collections.emptyMap(), "EVAL_MODEL", "PRODUCTION_MODEL"));
    }

    @Test
    public void shouldRejectBrokenSourceIntegrityEvenWithoutSearchErrors() {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("errors", 0);
        row.put("citationCoverage", 0.0);
        assertFalse(EvaluationLiveIntegrationTest.completeSources(row));
        row.put("citationCoverage", null);
        assertFalse(EvaluationLiveIntegrationTest.completeSources(row));
        row.put("citationCoverage", 1.0);
        assertTrue(EvaluationLiveIntegrationTest.completeSources(row));
        row.put("errors", 1);
        assertFalse(EvaluationLiveIntegrationTest.completeSources(row));
    }
}
