package io.github.sjcud.stockagent.rag.parser;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 纯文本解析器。
 *
 * <p>这是首版可验证的解析实现。PDF、HTML、Word 和表格解析应分别放入
 * 独立适配器，避免把格式判断和切片逻辑混在一个类中。</p>
 */
public class PlainTextDocumentParser implements DocumentParser {

    /** 支持的文本 MIME 类型。 */
    private static final String TEXT_PLAIN = "text/plain";
    private static final String TEXT_MARKDOWN = "text/markdown";

    /** 将 UTF-8 字节解析为统一文档模型。 */
    @Override
    public ParsedDocument parse(String documentId, String revision, byte[] content, String contentType) {
        if (!isSupported(contentType)) {
            throw new IllegalArgumentException("暂不支持的文档类型: " + contentType);
        }
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        String text = new String(content, StandardCharsets.UTF_8).trim();
        return new ParsedDocument(documentId, revision, text);
    }

    private boolean isSupported(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.trim().toLowerCase();
        return TEXT_PLAIN.equals(normalized) || TEXT_MARKDOWN.equals(normalized);
    }
}
