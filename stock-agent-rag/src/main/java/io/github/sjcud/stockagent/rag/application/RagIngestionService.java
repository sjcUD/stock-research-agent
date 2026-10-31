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
 * <p>该服务固定编排“解析 → 切片 → 写入”三个阶段。Embedding 由具体
 * VectorStore 适配器负责：首版 Qdrant 通过 Spring AI 的 EmbeddingModel
 * 在 add 时生成向量，核心服务不依赖供应商 SDK。</p>
 */
public class RagIngestionService {

    private final DocumentParser parser;
    private final FixedWindowChunkingStrategy chunkingStrategy;
    private final VectorStore vectorStore;

    public RagIngestionService(DocumentParser parser,
                               FixedWindowChunkingStrategy chunkingStrategy,
                               VectorStore vectorStore) {
        if (parser == null || chunkingStrategy == null || vectorStore == null) {
            throw new IllegalArgumentException("RAG 入库依赖不能为空");
        }
        this.parser = parser;
        this.chunkingStrategy = chunkingStrategy;
        this.vectorStore = vectorStore;
    }

    /** 解析 UTF-8 文档，按页一切片后写入向量存储。 */
    public IngestionResult ingest(String documentId,
                                  String revision,
                                  byte[] content,
                                  String contentType) {
        ParsedDocument document = parser.parse(documentId, revision, content, contentType);
        List<DocumentChunk> chunks = chunkingStrategy.chunk(
                document.getDocumentId(), document.getRevision(), 1, document.getText());
        vectorStore.upsert(chunks);
        return new IngestionResult(document.getDocumentId(), document.getRevision(), chunks.size());
    }

    /** 提供字符串入口，便于命令行探针和单元测试使用。 */
    public IngestionResult ingestText(String documentId, String revision, String text) {
        return ingest(documentId, revision, text.getBytes(StandardCharsets.UTF_8), "text/plain");
    }
}
