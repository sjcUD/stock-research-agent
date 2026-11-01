package io.github.sjcud.stockagent.rag.retrieval;

import java.util.List;

/** 无答案运行时决策：没有达到最低有效命中时返回资料不足，而不是把噪声交给模型。 */
public final class RetrievalAbstentionPolicy {
    private final boolean enabled;
    private final int minimumHits;
    public RetrievalAbstentionPolicy(boolean enabled, int minimumHits) {
        if (minimumHits < 1) throw new IllegalArgumentException("minimumHits 必须大于0");
        this.enabled = enabled; this.minimumHits = minimumHits;
    }
    /** 返回是否应拒答；策略关闭时永不阻断旧链路。 */
    public boolean shouldAbstain(List<RetrievalHit> hits) {
        return enabled && (hits == null || hits.size() < minimumHits);
    }
    public String message() { return "资料不足，无法判断；当前检索结果未达到可靠回答所需的证据数量。"; }
}
