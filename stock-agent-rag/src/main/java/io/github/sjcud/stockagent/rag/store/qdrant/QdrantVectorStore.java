package io.github.sjcud.stockagent.rag.store.qdrant;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.Citation;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Qdrant adapter。
 *
 * <p>底层客户端由 Spring AI QdrantVectorStore 注入，业务层只看到本项目 的 VectorStore 契约。这里负责 DocumentChunk 与 Spring
 * AI Document 的 转换，后续可在此处增加 collection、payload filter 和 generation 逻辑。
 */
public class QdrantVectorStore implements VectorStore {

    /** 框架存储适配，仍由 Spring AI 负责 Embedding 与 Qdrant 协议。 */
    private final org.springframework.ai.vectorstore.VectorStore delegate;

    /** 每次 add 的文档数，独立于文本 Token 大小限制。 */
    private final int batchSize;

    public QdrantVectorStore(org.springframework.ai.vectorstore.VectorStore delegate) {
        this(delegate, 10);
    }

    /** 为供应商配置请求条数上限；默认10是当前端点验证值，不是通用协议限制。 */
    public QdrantVectorStore(
            org.springframework.ai.vectorstore.VectorStore delegate, int batchSize) {
        if (delegate == null || batchSize <= 0) {
            throw new IllegalArgumentException("Qdrant delegate 不能为空");
        }
        this.delegate = delegate;
        this.batchSize = batchSize;
    }

    /** 将片段写入 Spring AI Qdrant 实现，保留引用所需元数据。 */
    @Override
    public void upsert(List<DocumentChunk> chunks) {
        List<Document> documents = new ArrayList<Document>();
        for (DocumentChunk chunk : chunks) {
            java.util.Map<String, Object> metadata = new java.util.HashMap<String, Object>();
            metadata.put("documentId", chunk.getDocumentId());
            metadata.put("documentRevision", chunk.getDocumentRevision());
            metadata.put("page", chunk.getPage());
            metadata.put("offset", chunk.getOffset());
            metadata.put("chunkId", chunk.getChunkId());
            String qdrantId =
                    UUID.nameUUIDFromBytes(
                                    chunk.getChunkId()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .toString();
            documents.add(new Document(qdrantId, chunk.getText(), metadata));
        }
        // Spring AI 的 Token 分批不保证条数上限，再按文档数量拆分。
        // 批次失败会向上抛错；稳定 ID 让调用方重试时覆盖已成功片段，而非生成重复记录。
        for (int start = 0; start < documents.size(); start += batchSize) {
            delegate.add(documents.subList(start, Math.min(start + batchSize, documents.size())));
        }
    }

    /** 将 Spring AI 命中转换为业务层 RetrievalHit。 */
    @Override
    public List<RetrievalHit> search(RetrievalQuery query) {
        List<Document> documents =
                delegate.similaritySearch(
                        SearchRequest.builder()
                                .query(query.getText())
                                .topK(query.getTopK())
                                .build());
        List<RetrievalHit> hits = new ArrayList<RetrievalHit>();
        for (Document document : documents) {
            double score = document.getScore() == null ? 0.0D : document.getScore().doubleValue();
            Object chunkId = document.getMetadata().get("chunkId");
            String stableChunkId = chunkId == null ? document.getId() : chunkId.toString();
            hits.add(
                    new RetrievalHit(stableChunkId, document.getText(), score, citation(document)));
        }
        return hits;
    }

    /** 从 Qdrant payload 恢复来源定位，供回答层回传引用。 */
    private Citation citation(Document document) {
        java.util.Map<String, Object> metadata = document.getMetadata();
        Object documentId = metadata.get("documentId");
        Object revision = metadata.get("documentRevision");
        Object page = metadata.get("page");
        Object offset = metadata.get("offset");
        if (documentId == null || revision == null || page == null || offset == null) {
            return null;
        }
        return new Citation(
                documentId.toString(),
                revision.toString(),
                ((Number) page).intValue(),
                ((Number) offset).intValue());
    }
}
