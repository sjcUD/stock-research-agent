package io.github.sjcud.stockagent.rag.store.qdrant;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.retrieval.Citation;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * Qdrant adapter。
 *
 * <p>底层客户端由 Spring AI QdrantVectorStore 注入，业务层只看到本项目
 * 的 VectorStore 契约。这里负责 DocumentChunk 与 Spring AI Document 的
 * 转换，后续可在此处增加 collection、payload filter 和 generation 逻辑。</p>
 */
public class QdrantVectorStore implements VectorStore {

    private final org.springframework.ai.vectorstore.VectorStore delegate;

    public QdrantVectorStore(org.springframework.ai.vectorstore.VectorStore delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Qdrant delegate 不能为空");
        }
        this.delegate = delegate;
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
            String qdrantId = UUID.nameUUIDFromBytes(
                    chunk.getChunkId().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            documents.add(new Document(qdrantId, chunk.getText(), metadata));
        }
        delegate.add(documents);
    }

    /** 将 Spring AI 命中转换为业务层 RetrievalHit。 */
    @Override
    public List<RetrievalHit> search(RetrievalQuery query) {
        List<Document> documents = delegate.similaritySearch(
                SearchRequest.builder().query(query.getText()).topK(query.getTopK()).build());
        List<RetrievalHit> hits = new ArrayList<RetrievalHit>();
        for (Document document : documents) {
            double score = document.getScore() == null ? 0.0D : document.getScore().doubleValue();
            Object chunkId = document.getMetadata().get("chunkId");
            String stableChunkId = chunkId == null ? document.getId() : chunkId.toString();
            hits.add(new RetrievalHit(stableChunkId, document.getText(), score, citation(document)));
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
        return new Citation(documentId.toString(), revision.toString(),
                ((Number) page).intValue(), ((Number) offset).intValue());
    }
}
