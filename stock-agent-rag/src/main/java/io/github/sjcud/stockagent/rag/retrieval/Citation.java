package io.github.sjcud.stockagent.rag.retrieval;

/**
 * 可回链的检索引用。
 *
 * <p>引用不保存完整文档内容，只保存文档版本和定位信息，供 Web 层展示
 * 来源抽屉或继续查询原文。</p>
 */
public final class Citation {

    private final String documentId;
    private final String revision;
    private final int page;
    private final int offset;

    public Citation(String documentId, String revision, int page, int offset) {
        this.documentId = documentId;
        this.revision = revision;
        this.page = page;
        this.offset = offset;
    }

    public String getDocumentId() { return documentId; }
    public String getRevision() { return revision; }
    public int getPage() { return page; }
    public int getOffset() { return offset; }
}
