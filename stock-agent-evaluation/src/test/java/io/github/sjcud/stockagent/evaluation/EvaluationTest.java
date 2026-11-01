package io.github.sjcud.stockagent.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.rag.retrieval.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 评测器自身也要有反例：不能把损坏引用、无答案或漏调工具打成满分。 */
public class EvaluationTest {
    @Test
    public void shouldLoadVersionedSyntheticDataset() throws Exception {
        EvaluationDataset dataset = EvaluationDataset.load();
        assertEquals("synthetic-v1", dataset.getVersion());
        assertEquals(8, dataset.getDocuments().size());
        assertEquals(16, dataset.getQuestions().size());
        assertEquals(64, dataset.getSha256().length());
    }

    @Test
    public void shouldLoadHardDatasetAndGenerateBlindTemplateWithoutAnswers() throws Exception {
        EvaluationDataset hard = EvaluationDataset.load("/evaluation/stock-agent-synthetic-v2-hard.json");
        assertEquals("synthetic-v2-hard", hard.getVersion());
        assertEquals(6, hard.getQuestions().size());
        java.nio.file.Path target = java.nio.file.Files.createTempFile("blind-review", ".csv");
        BlindReviewTemplate.write(target, hard);
        String csv = new String(java.nio.file.Files.readAllBytes(target), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("citation_ok,faithful,notes"));
        assertFalse(csv.contains("30亿元"));
    }

    @Test
    public void shouldScoreKnownRankingAndRejectForgedCitation() throws Exception {
        EvaluationDataset d = EvaluationDataset.load();
        EvaluationDataset.Question q = d.getQuestions().get(0);
        RetrievalHit correct =
                new RetrievalHit(
                        "alpha:v1:1:0",
                        d.getDocuments().get(0).getText(),
                        0.9,
                        new Citation("alpha", "v1", 1, 0));
        RetrievalHit wrong =
                new RetrievalHit("forged", "伪造文字", 1.0, new Citation("beta", "v1", 1, 0));
        RagEvaluator.Score s = new RagEvaluator(d).score(q, Arrays.asList(wrong, correct));
        assertEquals(1.0, s.recall, 1e-9);
        assertEquals(0.5, s.reciprocalRank, 1e-9);
        assertEquals(1, s.validCitations);
        assertFalse(
                new RagEvaluator(d)
                        .validCitation(
                                new RetrievalHit(
                                        "alpha:v1:1:0",
                                        "伪造",
                                        1,
                                        new Citation("alpha", "v1", 1, 0))));
    }

    @Test
    public void shouldTreatNoAnswerSeparately() throws Exception {
        EvaluationDataset d = EvaluationDataset.load();
        RagEvaluator.Score s =
                new RagEvaluator(d).score(d.getQuestions().get(10), Collections.emptyList());
        assertNull(s.recall);
        assertNull(s.hit);
        assertEquals(0.0, s.falsePositive, 1e-9);
        assertNull(s.citationCoverage());
    }

    @Test
    public void shouldApplyInclusiveSimilarityThresholdToNegativeQueries() throws Exception {
        EvaluationDataset d = EvaluationDataset.load();
        EvaluationDataset.Question q = d.getQuestions().get(10);
        RetrievalHit boundary = new RetrievalHit("noise", "无关片段", 0.50, null);
        RagEvaluator evaluator = new RagEvaluator(d);
        assertEquals(1.0, evaluator.score(q, Collections.singletonList(boundary), 0.50).falsePositive, 1e-9);
        assertEquals(0.0, evaluator.score(q, Collections.singletonList(boundary), 0.51).falsePositive, 1e-9);
    }

    @Test
    public void shouldRejectBadAnswerAndUnknownReferences() {
        AnswerEvaluator evaluator = new AnswerEvaluator();
        assertTrue(
                evaluator
                        .score(
                                "收入为120亿元[片段=a]",
                                Arrays.asList("120亿元"),
                                false,
                                Collections.singleton("a"))
                        .passed());
        assertFalse(
                evaluator
                        .score(
                                "收入为99亿元[片段=x]",
                                Arrays.asList("120亿元"),
                                false,
                                Collections.singleton("a"))
                        .passed());
        assertTrue(
                evaluator
                        .score("资料不足，无法判断", Collections.emptyList(), true, Collections.emptySet())
                        .passed());
        assertFalse(
                evaluator
                        .score("必然上涨", Collections.emptyList(), true, Collections.emptySet())
                        .passed());
    }

    @Test
    public void shouldCountDuplicateAndWrongToolCalls() {
        List<ToolCallEvaluator.Call> expected =
                Arrays.asList(new ToolCallEvaluator.Call("search", "收入", 5, true));
        List<ToolCallEvaluator.Call> actual =
                Arrays.asList(
                        expected.get(0),
                        expected.get(0),
                        new ToolCallEvaluator.Call("search", "收入", 1, true));
        ToolCallEvaluator.Score score = new ToolCallEvaluator().score(expected, actual);
        assertEquals(1.0 / 3, score.precision, 1e-9);
        assertEquals(1.0, score.recall, 1e-9);
        assertFalse(score.exactSequence);
    }

    @Test
    public void shouldRunReproducibleAblations() throws Exception {
        EvaluationSuite suite = new EvaluationSuite(EvaluationDataset.load());
        java.util.List<java.util.Map<String, Object>> results = suite.offline();
        assertEquals(18, results.size());
        String output = System.getProperty("stock.eval.report");
        if (output != null)
            suite.writeReport(
                    java.nio.file.Paths.get(output), "offline", results, suite.scriptedWorkflow());
        java.util.Map<String, Object> workflow = suite.scriptedWorkflow();
        assertTrue(((AnswerEvaluator.Score) workflow.get("answerRules")).passed());
        assertTrue(((ToolCallEvaluator.Score) workflow.get("toolContract")).exactSequence);
        List<Map<String, Object>> repeated = suite.offline();
        for (int i = 0; i < results.size(); i++) {
            assertEquals(0, results.get(i).get("errors"));
            assertEquals(1.0, results.get(i).get("citationCoverage"));
            for (String key :
                    Arrays.asList(
                            "recallAtK", "hitAtK", "mrr", "citationCoverage", "negativeAnyHitRate"))
                assertEquals(results.get(i).get(key), repeated.get(i).get(key));
        }
        List<Map<String, Object>> thresholded = suite.offlineWithThreshold(0.9);
        assertEquals(18, thresholded.size());
        assertEquals(0.9, thresholded.get(0).get("similarityThreshold"));
    }

    /** 所有失败查询仍进入分母，不能只统计成功子集。 */
    @Test
    public void shouldKeepSearchErrorsInDenominator() throws Exception {
        io.github.sjcud.stockagent.rag.store.VectorStore broken =
                new io.github.sjcud.stockagent.rag.store.VectorStore() {
                    public void upsert(
                            List<io.github.sjcud.stockagent.rag.chunking.DocumentChunk> chunks) {}

                    public List<RetrievalHit> search(RetrievalQuery query) {
                        throw new IllegalStateException("failure");
                    }
                };
        Map<String, Object> row =
                new EvaluationSuite(EvaluationDataset.load()).evaluate(broken, "test", 8, 32, 1);
        assertEquals(16, row.get("errors"));
        assertEquals(0.0, row.get("recallAtK"));
        assertEquals(1.0, row.get("negativeAnyHitRate"));
        assertNull(row.get("citationCoverage"));
    }

    /** 维度错误、零向量和非有限数均应阻止索引污染。 */
    @Test
    public void shouldRejectInvalidEmbeddingSpaces() {
        for (float[] vector :
                new float[][] {new float[] {1}, new float[] {0, 0}, new float[] {Float.NaN, 1}}) {
            EvaluationVectorIndex index =
                    new EvaluationVectorIndex(texts -> Collections.singletonList(vector), 2);
            assertThrows(
                    IllegalStateException.class, () -> index.search(new RetrievalQuery("test", 1)));
        }
    }

    /** 有效片段重复只算一个相关文档，不因多片段虚增召回。 */
    @Test
    public void shouldDeduplicateDocumentRanks() throws Exception {
        EvaluationDataset d = EvaluationDataset.load();
        String text = d.getDocuments().get(0).getText();
        RetrievalHit h =
                new RetrievalHit("alpha:v1:1:0", text, 1, new Citation("alpha", "v1", 1, 0));
        RagEvaluator.Score score =
                new RagEvaluator(d).score(d.getQuestions().get(0), Arrays.asList(h, h));
        assertEquals(1.0, score.recall);
        assertEquals(1.0, score.reciprocalRank);
    }

    /** 仅关键词通过但无引用的回答仍必须失败。 */
    @Test
    public void shouldRejectMissingCitation() {
        assertFalse(
                new AnswerEvaluator()
                        .score(
                                "120亿元",
                                Collections.singletonList("120亿元"),
                                false,
                                Collections.singleton("a"))
                        .passed());
    }

    @Test
    public void shouldMeasureLexicalFaithfulnessAndDeduplicateFacts() {
        AnswerEvaluator evaluator = new AnswerEvaluator();
        Map<String, String> evidence = new LinkedHashMap<String, String>();
        evidence.put("a", "收入为120亿元");
        AnswerEvaluator.FaithfulnessScore supported = evaluator.faithfulness(
                "收入为120亿元[片段=a]", Arrays.asList("120亿元", "120亿元"), evidence);
        assertEquals(1, supported.supportedFacts);
        assertEquals(1.0, supported.coverage(), 1e-9);
        assertTrue(supported.passed());
        AnswerEvaluator.FaithfulnessScore unsupported = evaluator.faithfulness(
                "收入为99亿元[片段=a]", Collections.singletonList("99亿元"), evidence);
        assertEquals(1, unsupported.unsupportedFacts);
        assertFalse(unsupported.passed());
        assertEquals(1, evaluator.faithfulness("收入为120亿元[片段=unknown]",
                Collections.singletonList("120亿元"), evidence).invalidCitations);
    }

    /** 多文档问题区分 Hit 与 Recall：找到一篇算命中，但召回率只有一半。 */
    @Test
    public void shouldDistinguishRecallFromHit() throws Exception {
        EvaluationDataset d = EvaluationDataset.load();
        RetrievalHit hit =
                new RetrievalHit(
                        "alpha:v1:1:0",
                        d.getDocuments().get(0).getText(),
                        1,
                        new Citation("alpha", "v1", 1, 0));
        RagEvaluator.Score score =
                new RagEvaluator(d).score(d.getQuestions().get(12), Collections.singletonList(hit));
        assertEquals(0.5, score.recall);
        assertEquals(1.0, score.hit);
    }
}
