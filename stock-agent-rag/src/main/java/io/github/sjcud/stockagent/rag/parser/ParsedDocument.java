package io.github.sjcud.stockagent.rag.parser;

/** 解析阶段的统一文档模型，保留后续引用需要的版本信息。 */
public final class ParsedDocument {

    private final String documentId;
    private final String revision;
    private final String text;

    public ParsedDocument(String documentId, String revision, String text) {
        this.documentId = requireText(documentId, "documentId");
        this.revision = requireText(revision, "revision");
        this.text = requireText(text, "text");
    }

    /** 返回文档标识。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 返回文档版本。 */
    public String getRevision() {
        return revision;
    }

    /** 返回解析后的纯文本。 */
    public String getText() {
        return text;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
