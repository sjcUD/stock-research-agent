package io.github.sjcud.stockagent.rag.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 默认按向量分数降序重排，并用 chunkId 作为同分时的确定性次序。 */
public final class ScoreRetrievalReranker implements RetrievalReranker {
    /** 返回不修改底层存储列表的稳定副本。 */
    @Override
    public List<RetrievalHit> rerank(RetrievalQuery query, List<RetrievalHit> candidates) {
        List<RetrievalHit> sorted = new ArrayList<RetrievalHit>(candidates);
        Collections.sort(sorted, new Comparator<RetrievalHit>() {
            @Override public int compare(RetrievalHit left, RetrievalHit right) {
                int score = Double.compare(right.getScore(), left.getScore());
                return score != 0 ? score : left.getChunkId().compareTo(right.getChunkId());
            }
        });
        return sorted;
    }
}
