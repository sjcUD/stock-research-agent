package io.github.sjcud.stockagent.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.rag.store.*;
import io.github.sjcud.stockagent.rag.retrieval.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 三套语料和阈值对照的可复现验收入口；默认无网络，有输出目录时保存机器报告。 */
public class P11OfflineAcceptanceTest {
    @Test public void shouldRunCorporaThresholdAndStabilityComparisons() throws Exception {
        String external = System.getProperty("stock.eval.dataset");
        List<EvaluationDataset> corpora = external == null ? Arrays.asList(EvaluationDataset.load(),
                EvaluationDataset.load("/evaluation/stock-agent-synthetic-v2-hard.json"),
                EvaluationDataset.load("/evaluation/stock-agent-public-v1.json")) : Arrays.asList(EvaluationDataset.load(Paths.get(external)));
        for (EvaluationDataset corpus : corpora) {
            EvaluationSuite suite = new EvaluationSuite(corpus);
            List<Map<String, Object>> rows = suite.offline();
            for (double threshold : new double[] {.3, .5, .7}) rows.addAll(suite.offlineWithThreshold(threshold));
            assertEquals(72, rows.size());
            for (Map<String, Object> row : rows) assertEquals(0, row.get("errors"));
            // 同一独立索引重复30轮，观测执行稳定性，不将重复样本当作新语义数据。
            VectorStore index = new EvaluationVectorIndex(EvaluationVectorIndex.hashBaseline(128), 128);
            index.upsert(suite.chunks(64));
            List<String> reference = ids(index.search(new RetrievalQuery(corpus.getQuestions().get(0).getText(), 3)));
            EvaluationStatistics stability = new EvaluationStatistics();
            for (int round = 0; round < 30; round++) {
                long started = System.nanoTime(); boolean identical = true;
                for (EvaluationDataset.Question q : corpus.getQuestions()) {
                    List<RetrievalHit> result = index.search(new RetrievalQuery(q.getText(), 3));
                    assertNotNull(result);
                }
                identical = reference.equals(ids(index.search(new RetrievalQuery(corpus.getQuestions().get(0).getText(), 3))));
                stability.record((System.nanoTime() - started) / 1000000.0, identical); assertTrue(identical);
            }
            String output = System.getProperty("stock.eval.report");
            if (output != null) {
                Map<String, Object> extra = new LinkedHashMap<String, Object>(); extra.put("repeatRounds", stability.snapshot());
                extra.put("scope", "offline hash baseline; repeat rounds are not independent semantic samples");
                suite.writeReport(Paths.get(output), corpus.getVersion() + "-acceptance", rows, extra);
                BlindReviewTemplate.write(Paths.get(output, corpus.getVersion() + "-blind-template.csv"), corpus);
            }
        }
    }
    /** 检索顺序与引用ID在同一输入下必须一致。 */
    private List<String> ids(List<RetrievalHit> hits) { List<String> ids = new ArrayList<String>(); for (RetrievalHit h : hits) ids.add(h.getChunkId()); return ids; }
}
