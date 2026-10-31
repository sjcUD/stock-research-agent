package io.github.sjcud.stockagent.rag.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证文本解析器保留文档标识和原文内容。 */
public class PlainTextDocumentParserTest {

    @Test
    public void shouldParseUtf8Text() {
        PlainTextDocumentParser parser = new PlainTextDocumentParser();

        ParsedDocument result = parser.parse(
                "公告-001", "rev-1", "公司收入增长".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertEquals("公告-001", result.getDocumentId());
        assertEquals("rev-1", result.getRevision());
        assertEquals("公司收入增长", result.getText());
    }

    @Test
    public void shouldRejectUnsupportedContentType() {
        PlainTextDocumentParser parser = new PlainTextDocumentParser();

        assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "doc", "rev", "x".getBytes(StandardCharsets.UTF_8), "application/pdf"));
    }
}
