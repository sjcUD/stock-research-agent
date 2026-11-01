package io.github.sjcud.stockagent.rag.application;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.chunking.FixedWindowChunkingStrategy;
import io.github.sjcud.stockagent.rag.parser.DocumentParser;
import io.github.sjcud.stockagent.rag.parser.ParsedDocument;
import io.github.sjcud.stockagent.rag.store.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RAG 入库应用服务。
 *
 * <p>该服务固定编排“解析 → 切片 → 写入”三个阶段。Embedding 由具体 VectorStore 适配器负责：首版 Qdrant 通过 Spring AI 的
 * EmbeddingModel 在 add 时生成向量，核心服务不依赖供应商 SDK。
 */
public class RagIngestionService {

    private final DocumentParser parser;
    private final FixedWindowChunkingStrategy chunkingStrategy;
    private final VectorStore vectorStore;

    /** 可选原文归档；旧构造入口保持无仓储兼容。 */
    private final io.github.sjcud.stockagent.rag.document.DocumentRepository documents;

    public RagIngestionService(
            DocumentParser parser,
            FixedWindowChunkingStrategy chunkingStrategy,
            VectorStore vectorStore) {
        this(parser, chunkingStrategy, vectorStore, null);
    }

    /** 配置原文归档的完整入库链路。 */
    public RagIngestionService(
            DocumentParser parser,
            FixedWindowChunkingStrategy chunkingStrategy,
            VectorStore vectorStore,
            io.github.sjcud.stockagent.rag.document.DocumentRepository documents) {
        if (parser == null || chunkingStrategy == null || vectorStore == null) {
            throw new IllegalArgumentException("RAG 入库依赖不能为空");
        }
        this.parser = parser;
        this.chunkingStrategy = chunkingStrategy;
        this.vectorStore = vectorStore;
        this.documents = documents;
    }

    /** 解析文档，按实体页或逻辑全文切片后写入向量存储。 */
    public IngestionResult ingest(
            String documentId, String revision, byte[] content, String contentType) {
        ParsedDocument document = parser.parse(documentId, revision, content, contentType);
        List<DocumentChunk> chunks = new java.util.ArrayList<DocumentChunk>();
        for (io.github.sjcud.stockagent.rag.parser.ParsedPage page : document.getPages()) {
            if (!page.getText().trim().isEmpty())
                chunks.addAll(
                        chunkingStrategy.chunk(
                                document.getDocumentId(),
                                document.getRevision(),
                                page.getPage(),
                                page.getText()));
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("未提取到可索引文本");
        if (documents != null) {
            documents.stage(document, content, contentType);
            io.github.sjcud.stockagent.rag.document.StoredDocument existing = documents.find(documentId, revision);
            // 不可变版本已完成时直接幂等返回，避免重复批量写入扰动已发布版本。
            if ("INDEXED".equals(existing.getStatus())) return new IngestionResult(documentId, revision, existing.getChunkCount());
        }
        try {
            vectorStore.upsert(chunks);
            if (documents != null) documents.indexed(documentId, revision, chunks.size());
        } catch (RuntimeException failure) {
            if (documents != null)
                try {
                    documents.failed(documentId, revision);
                } catch (RuntimeException archiveFailure) {
                    failure.addSuppressed(archiveFailure);
                }
            throw failure;
        }
        return new IngestionResult(document.getDocumentId(), document.getRevision(), chunks.size());
    }

    /** 提供字符串入口，便于命令行探针和单元测试使用。 */
    public IngestionResult ingestText(String documentId, String revision, String text) {
        return ingest(documentId, revision, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }
}
