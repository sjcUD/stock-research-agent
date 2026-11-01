package io.github.sjcud.stockagent.rag.document;

import io.github.sjcud.stockagent.rag.parser.ParsedDocument;

/** 不可变文档归档视图，状态与正文分开，不能把保存完成当作索引成功。 */
public final class StoredDocument {
    private final ParsedDocument document;
    private final String contentType, status, sha256;
    private final long size;
    private final int chunkCount;

    public StoredDocument(
            ParsedDocument document,
            String type,
            String status,
            String hash,
            long size,
            int chunks) {
        this.document = document;
        contentType = type;
        this.status = status;
        sha256 = hash;
        this.size = size;
        chunkCount = chunks;
    }

    /** 返回不可变解析结果，包括页码、OCR标记和页内文本。 */
    public ParsedDocument getDocument() {
        return document;
    }

    /** 返回上传时确定的媒体类型。 */
    public String getContentType() {
        return contentType;
    }

    /** 返回PENDING、INDEXED或FAILED，供检索闸门判断。 */
    public String getStatus() {
        return status;
    }

    /** 返回原始字节指纹，用于同版本幂等校验。 */
    public String getSha256() {
        return sha256;
    }

    /** 返回原文件字节数。 */
    public long getSize() {
        return size;
    }

    /** 返回成功写入向量索引的片段数。 */
    public int getChunkCount() {
        return chunkCount;
    }
}
