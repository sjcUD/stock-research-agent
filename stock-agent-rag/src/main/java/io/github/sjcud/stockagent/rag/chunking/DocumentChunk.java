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

    /**
     * 返回由来源和偏移组成的稳定片段标识。
     *
     * <p>稳定 ID 用于幂等写入、引用回链和后续删除旧版本，不能使用
     * 每次进程启动都会变化的随机 UUID。</p>
     */
    public String getChunkId() {
        return documentId + ":" + documentRevision + ":" + page + ":" + offset;
    }
}
