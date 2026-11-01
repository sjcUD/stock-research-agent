package io.github.sjcud.stockagent.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** P11收口验收：空回答、语义评审失败及真实语料必须有独立反例。 */
public class P11CompletionTest {
    @Test public void missingFactsMustNotPassFaithfulness() {
        assertFalse(new AnswerEvaluator().faithfulness("", Arrays.asList("120亿元"),
                Collections.singletonMap("a", "120亿元")).passed());
    }
    @Test public void shouldLoadPublicCorpusWithEvidenceAnnotations() throws Exception {
        EvaluationDataset dataset = EvaluationDataset.load("/evaluation/stock-agent-public-v1.json");
        assertEquals("public-education-v1", dataset.getVersion());
        assertTrue(dataset.getQuestions().size() >= 10);
        assertTrue(dataset.getDocuments().get(0).getSourceUrl().startsWith("https://"));
        assertFalse(dataset.getQuestions().get(0).getEvidence().isEmpty());
        assertEquals(18, new EvaluationSuite(dataset).offline().size());
    }
    @Test public void documentHitMustNotImplyAnnotatedEvidenceRecall() throws Exception {
        EvaluationDataset d = EvaluationDataset.load("/evaluation/stock-agent-public-v1.json");
        EvaluationDataset.Source source = d.getDocuments().get(1);
        io.github.sjcud.stockagent.rag.retrieval.RetrievalHit partial = new io.github.sjcud.stockagent.rag.retrieval.RetrievalHit(
                source.getId() + ":" + source.getRevision() + ":1:0", source.getText().substring(0, 4), 1,
                new io.github.sjcud.stockagent.rag.retrieval.Citation(source.getId(), source.getRevision(), 1, 0));
        RagEvaluator.Score score = new RagEvaluator(d).score(d.getQuestions().get(0), Arrays.asList(partial));
        assertEquals(1.0, score.hit); assertEquals(0.0, score.evidenceRecall);
    }
    @Test public void shouldRejectMalformedSemanticJudgeAndUnknownEvidence() {
        assertThrows(IllegalStateException.class, () -> new ModelSemanticJudge(p -> "not JSON").judge("q", "a", Collections.emptyMap(), Arrays.asList("fact")));
        String unknown = "{\"claims\":[{\"text\":\"收入120亿元\",\"supported\":true,\"evidenceIds\":[\"forged\"]}],\"missingFacts\":[],\"abstentionCorrect\":true}";
        assertThrows(IllegalStateException.class, () -> new ModelSemanticJudge(p -> unknown).judge("q", "收入120亿元", Collections.singletonMap("a", "收入120亿元"), Arrays.asList("收入120亿元")));
    }
    @Test public void shouldScoreUnsupportedExtraClaimAndMissingFacts() {
        String result = "{\"claims\":[{\"text\":\"收入120亿元\",\"supported\":true,\"evidenceIds\":[\"a\"]},{\"text\":\"明年必涨\",\"supported\":false,\"evidenceIds\":[]}],\"missingFacts\":[\"现金流\"],\"abstentionCorrect\":true}";
        SemanticJudge.Result score = new ModelSemanticJudge(p -> result).judge("q", "收入120亿元，明年必涨", Collections.singletonMap("a", "收入120亿元"), Arrays.asList("收入120亿元", "现金流"));
        assertFalse(score.passed()); assertEquals(1, score.unsupportedClaims); assertEquals(1, score.missingFacts.size());
    }
    @Test public void shouldRejectBlindReviewComparisonsAcrossDifferentAnswers() throws Exception {
        Path one = Files.createTempFile("review1", ".csv"), two = Files.createTempFile("review2", ".csv");
        String header = "dataset,questionId,question,answer,citation_ok,faithful,notes\n";
        Files.write(one, (header + "v1,q1,q,answer1,true,true,\n").getBytes("UTF-8"));
        Files.write(two, (header + "v1,q1,q,answer2,false,false,\n").getBytes("UTF-8"));
        try { assertThrows(IllegalArgumentException.class, () -> new BlindReviewAggregator().aggregate(Arrays.asList(one, two))); }
        finally { Files.deleteIfExists(one); Files.deleteIfExists(two); }
    }
    @Test public void shouldReportIntervalsLatencyAndUnknownCost() {
        EvaluationStatistics stats = new EvaluationStatistics();
        stats.record(10, true); stats.record(20, false); stats.record(30, true);
        assertEquals(30.0, stats.snapshot().get("latencyP95Millis"));
        assertEquals(3, stats.snapshot().get("samples"));
        assertTrue((Double) stats.snapshot().get("successLower95") < 2.0 / 3);
    }
}
