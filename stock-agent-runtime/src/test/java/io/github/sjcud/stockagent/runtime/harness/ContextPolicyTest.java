package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证压缩结果仍保留证券、日期和引用。 */
public class ContextPolicyTest {

    @Test
    public void shouldKeepProtectedResearchFields() {
        String result = new ContextPolicy().prepare(
                "股票=600519\n日期=2026-10-30\n引用=citation-1\n" + repeat("历史", 40), 40);

        assertTrue(result.contains("600519"));
        assertTrue(result.contains("2026-10-30"));
        assertTrue(result.contains("citation-1"));
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
