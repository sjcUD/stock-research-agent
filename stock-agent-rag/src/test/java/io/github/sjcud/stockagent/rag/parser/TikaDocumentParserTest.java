package io.github.sjcud.stockagent.rag.parser;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证 Tika 多格式解析器的文本提取和类型边界。 */
public class TikaDocumentParserTest {

    @Test
    public void shouldExtractHtmlText() {
        TikaDocumentParser parser = new TikaDocumentParser();
        ParsedDocument result = parser.parse(
                "html-001",
                "rev-1",
                "<html><body><h1>收入报告</h1><p>利润增长</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8),
                "text/html; charset=UTF-8");

        assertTrue(result.getText().contains("收入报告"));
        assertTrue(result.getText().contains("利润增长"));
    }

    @Test
    public void shouldRejectUnsupportedType() {
        TikaDocumentParser parser = new TikaDocumentParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "binary-001", "rev-1", new byte[] {1, 2, 3}, "image/png"));
    }
}
