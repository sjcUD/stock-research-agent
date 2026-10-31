package io.github.sjcud.stockagent.rag.store;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import java.util.List;

/** 向量存储的后端无关契约。 */
public interface VectorStore {
    /** 写入一批已经完成元数据校验的片段。 */
    void upsert(List<DocumentChunk> chunks);

    /** 按查询返回最相关片段。 */
    List<RetrievalHit> search(RetrievalQuery query);
}
