package io.github.sjcud.stockagent.rag.store;

import io.github.sjcud.stockagent.rag.document.DocumentRepository;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import java.util.*;

/** 完成状态作为检索提交闸门：分批向量写入失败时，部分片段仍不能用于回答。 */
public final class IndexedDocumentVectorStore implements VectorStore {
    /** 真实向量后端，不承担跨文件/数据库分布式事务。 */
    private final VectorStore delegate;
    /** 原文版本状态仓储；读取异常失败关闭，不能绕过状态。 */
    private final DocumentRepository documents;
    /** 装饰器组合，不扩散Qdrant专有状态协议。 */
    public IndexedDocumentVectorStore(VectorStore delegate, DocumentRepository documents) {
        this.delegate=Objects.requireNonNull(delegate);this.documents=Objects.requireNonNull(documents);
    }
    /** 写入由入库应用服务在成功后标记INDEXED；PENDING不可见。 */
    @Override public void upsert(List<DocumentChunk> chunks) { delegate.upsert(chunks); }
    /** 历史未归档版本兼容可见，新版本必须有INDEXED状态；不为补足topK无界追加检索。 */
    @Override public List<RetrievalHit> search(RetrievalQuery query) {
        List<RetrievalHit> visible=new ArrayList<RetrievalHit>();
        Map<String,Boolean> states=new HashMap<String,Boolean>();
        for(RetrievalHit hit:delegate.search(query)) {
            Citation citation=hit.getCitation();if(citation==null) continue;
            String key=citation.getDocumentId()+"\u0000"+citation.getRevision();Boolean indexed=states.get(key);
            if(indexed==null) {
                try { indexed="INDEXED".equals(documents.find(citation.getDocumentId(),citation.getRevision()).getStatus()); }
                catch(NoSuchElementException legacy) { indexed=true; }
                states.put(key,indexed);
            }
            if(indexed) visible.add(hit);
        }
        return Collections.unmodifiableList(visible);
    }
}
