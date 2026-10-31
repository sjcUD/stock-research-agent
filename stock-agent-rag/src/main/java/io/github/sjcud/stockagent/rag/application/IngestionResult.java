package io.github.sjcud.stockagent.rag.application;

/** 文档入库结果，供 API 和运行记录使用。 */
public final class IngestionResult {

    private final String documentId;
    private final String revision;
    private final int chunkCount;

    public IngestionResult(String documentId, String revision, int chunkCount) {
        this.documentId = documentId;
        this.revision = revision;
        this.chunkCount = chunkCount;
    }

    /** 返回文档标识。 */
    public String getDocumentId() {
        return documentId;
    }

    /** 返回文档版本。 */
    public String getRevision() {
        return revision;
    }

    /** 返回本次写入的片段数量。 */
    public int getChunkCount() {
        return chunkCount;
    }
}
