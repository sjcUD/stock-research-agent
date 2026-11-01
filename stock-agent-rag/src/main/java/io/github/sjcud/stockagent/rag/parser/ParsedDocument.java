package io.github.sjcud.stockagent.rag.parser;

/** 解析阶段的统一文档模型，保留后续引用需要的版本信息。 */
public final class ParsedDocument {

    private final String documentId;
    private final String revision;
    private final String text;

    /** 页内容与页码精度分开，Office全文不伪装成PDF实体页。 */
    private final java.util.List<ParsedPage> pages;

    private final boolean physicalPages;

    public ParsedDocument(String documentId, String revision, String text) {
        this(
                documentId,
                revision,
                java.util.Collections.singletonList(
                        new ParsedPage(1, requireText(text, "text"), false)),
                false);
    }

    /** 创建逐页文档，必须按原文件页号连续排列并保留空白页。 */
    public ParsedDocument(
            String documentId,
            String revision,
            java.util.List<ParsedPage> pages,
            boolean physicalPages) {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("页列表不能为空");
        StringBuilder combined = new StringBuilder();
        int expected = 1;
        for (ParsedPage page : pages) {
            if (page == null || page.getPage() != expected++)
                throw new IllegalArgumentException("页号必须从1连续排列");
            if (combined.length() > 0) combined.append('\n');
            combined.append(page.getText());
        }
        this.pages =
                java.util.Collections.unmodifiableList(new java.util.ArrayList<ParsedPage>(pages));
        this.physicalPages = physicalPages;
        this.documentId = requireText(documentId, "documentId");
        this.revision = requireText(revision, "revision");
        this.text = requireText(combined.toString(), "text");
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

    /** 返回不可变页列表。 */
    public java.util.List<ParsedPage> getPages() {
        return pages;
    }

    /** true仅用于已按PDF实体页提取的文档。 */
    public boolean isPhysicalPages() {
        return physicalPages;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
