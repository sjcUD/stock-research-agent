package io.github.sjcud.stockagent.web.operations;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 日志输出整体脱敏，既覆盖正文也覆盖多行异常，不改变普通业务字段。 */
public class SecretRedactorTest {
    @Test
    public void shouldMaskConfiguredSecretsLiterally() {
        SecretRedactor redactor =
                new SecretRedactor(Arrays.asList("key-$5\\suffix", "pass-1234", ""));
        String output = redactor.redact("request key-$5\\suffix\nCaused by: password=pass-1234");
        assertFalse(output.contains("key-$5\\suffix"));
        assertFalse(output.contains("pass-1234"));
        assertTrue(output.contains("Caused by"));
    }

    @Test
    public void shouldMaskCommonCredentialFields() {
        String text =
                "Authorization: Bearer test-token-123\n"
                    + "{\"api_key\":\"private-token-345\",\"password\":\"private-password\"}";
        String output = new SecretRedactor(Collections.emptyList()).redact(text);
        assertFalse(output.contains("test-token-123"));
        assertFalse(output.contains("private-token-345"));
        assertFalse(output.contains("private-password"));
    }

    @Test
    public void shouldKeepOrdinaryMessages() {
        assertEquals(
                "600519 price=1680 rows=12",
                new SecretRedactor(Collections.emptyList()).redact("600519 price=1680 rows=12"));
    }
}
