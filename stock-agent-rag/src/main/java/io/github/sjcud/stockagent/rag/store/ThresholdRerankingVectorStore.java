package io.github.sjcud.stockagent.rag.store;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalReranker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 检索策略装饰器：过滤低于最低分的候选，再交给重排器。
 *
 * <p>阈值采用大于等于比较；默认构造值应为0，以兼容历史向量库的返回行为。</p>
 */
public final class ThresholdRerankingVectorStore implements VectorStore {
    private final VectorStore delegate;
    private final double minimumScore;
    private final RetrievalReranker reranker;

    /** 创建可插拔的阈值与重排链。 */
    public ThresholdRerankingVectorStore(VectorStore delegate, double minimumScore,
            RetrievalReranker reranker) {
        if (delegate == null || reranker == null || Double.isNaN(minimumScore)) {
            throw new IllegalArgumentException("检索策略依赖无效");
        }
        this.delegate = delegate;
        this.minimumScore = minimumScore;
        this.reranker = reranker;
    }

    /** 入库不经过检索策略，原样交给后端。 */
    @Override public void upsert(List<DocumentChunk> chunks) { delegate.upsert(chunks); }

    /** 过滤低分候选并截回查询要求的 topK，避免重排器意外扩大响应。 */
    @Override public List<RetrievalHit> search(RetrievalQuery query) {
        List<RetrievalHit> raw = delegate.search(query);
        List<RetrievalHit> filtered = new ArrayList<RetrievalHit>();
        for (RetrievalHit hit : raw) if (hit != null && hit.getScore() >= minimumScore) filtered.add(hit);
        List<RetrievalHit> ranked = reranker.rerank(query, filtered);
        if (ranked == null || ranked.isEmpty()) return Collections.emptyList();
        return new ArrayList<RetrievalHit>(ranked.subList(0, Math.min(query.getTopK(), ranked.size())));
    }
}
