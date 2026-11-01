package io.github.sjcud.stockagent.evaluation;

import io.github.sjcud.stockagent.rag.retrieval.*;

import java.util.*;

/** 按文档去重计算召回和 MRR；引用完整性只验证可回链，不等价于语义支持。 */
public final class RagEvaluator {
    /** 固定原文用于独立校验来源，不参与检索排序。 */
    private final EvaluationDataset dataset;

    /** 绑定本次评测所需的独立依赖与模型配置。 */
    public RagEvaluator(EvaluationDataset dataset) {
        this.dataset = Objects.requireNonNull(dataset);
    }

    /** 正例宏平均由报告层完成；无答案问题只产生 falsePositive 指标。 */
    public Score score(EvaluationDataset.Question q, List<RetrievalHit> hits) {
        Set<String> found = new HashSet<String>();
        double rr = 0;
        int valid = 0, rank = 0;
        Map<String, Integer> ranked = new HashMap<String, Integer>();
        for (RetrievalHit hit : hits) {
            if (validCitation(hit)) valid++;
            Citation c = hit.getCitation();
            String doc = c == null ? "missing:" + hit.getChunkId() : c.getDocumentId();
            if (!ranked.containsKey(doc)) ranked.put(doc, ++rank);
            if (q.getRelevant().contains(doc) && validCitation(hit)) {
                found.add(doc);
                rr = Math.max(rr, 1.0 / ranked.get(doc));
            }
        }
        boolean negative = q.getRelevant().isEmpty();
        return new Score(
                negative ? null : (double) found.size() / q.getRelevant().size(),
                negative ? null : found.isEmpty() ? 0.0 : 1.0,
                negative ? null : rr,
                negative ? hits.isEmpty() ? 0.0 : 1.0 : null,
                valid,
                hits.size());
    }

    /** 检查来源、版本、页码、偏移、片段 ID 和原文逐字符一致，拒绝伪造 payload。 */
    public boolean validCitation(RetrievalHit hit) {
        Citation c = hit.getCitation();
        if (c == null || c.getPage() != 1 || c.getOffset() < 0) return false;
        for (EvaluationDataset.Source source : dataset.getDocuments()) {
            if (source.getId().equals(c.getDocumentId())
                    && source.getRevision().equals(c.getRevision())) {
                String expected =
                        source.getId() + ":" + source.getRevision() + ":1:" + c.getOffset();
                return expected.equals(hit.getChunkId())
                        && hit.getText() != null
                        && !hit.getText().isEmpty()
                        && c.getOffset() <= source.getText().length()
                        && source.getText().startsWith(hit.getText(), c.getOffset());
            }
        }
        return false;
    }

    /** 单问题计分，空命中时引用覆盖率为 null，不能伪装成100%。 */
    public static final class Score {
        /** null 表示不适用；文档级指标范围0到1。 */
        public final Double recall, hit, reciprocalRank, falsePositive;

        /** 可回链引用数与命中片段总数。 */
        public final int validCitations, totalHits;

        private Score(Double recall, Double hit, Double rr, Double fp, int valid, int count) {
            this.recall = recall;
            this.hit = hit;
            this.reciprocalRank = rr;
            this.falsePositive = fp;
            this.validCitations = valid;
            this.totalHits = count;
        }

        /** 无命中时不适用，有命中时返回实际可回链比例。 */
        public Double citationCoverage() {
            return totalHits == 0 ? null : (double) validCitations / totalHits;
        }
    }
}
