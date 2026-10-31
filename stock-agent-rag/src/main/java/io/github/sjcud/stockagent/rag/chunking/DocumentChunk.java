package io.github.sjcud.stockagent.rag.chunking;

/**
 * 文档检索片段。
 *
 * <p>片段同时保存文本和定位信息；定位信息用于引用，不能只保留向量
 * 或纯文本，否则回答无法回到原始页。</p>
 */
public final class DocumentChunk {
    private final String documentId;
    private final String documentRevision;
    private final int page;
    private final int offset;
    private final String text;

    public DocumentChunk(String documentId, String documentRevision, int page, int offset, String text) {
        this.documentId = documentId;
        this.documentRevision = documentRevision;
        this.page = page;
        this.offset = offset;
        this.text = text;
    }

    public String getDocumentId() { return documentId; }
    public String getDocumentRevision() { return documentRevision; }
    public int getPage() { return page; }
    public int getOffset() { return offset; }
    public String getText() { return text; }
}
