package io.github.sjcud.stockagent.rag.retrieval;

import java.util.List;

/** 可替换的检索结果重排策略；不负责生成或修改证据正文。 */
public interface RetrievalReranker {
    /** 根据查询和候选命中返回稳定排序后的结果。 */
    List<RetrievalHit> rerank(RetrievalQuery query, List<RetrievalHit> candidates);
}
