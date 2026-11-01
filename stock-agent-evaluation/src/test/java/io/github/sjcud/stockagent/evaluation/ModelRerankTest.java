package io.github.sjcud.stockagent.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
/** 模型重排不得添加或修改证据，返回分数必须逐候选对齐。 */
public class ModelRerankTest {
 @Test public void modelCanPromoteRelevantCandidateWithoutChangingCitation() {
  ModelRetrievalReranker ranker = new ModelRetrievalReranker(p -> "[0.1,0.95]");
  io.github.sjcud.stockagent.rag.retrieval.RetrievalHit first = new io.github.sjcud.stockagent.rag.retrieval.RetrievalHit("a", "研发7亿元", .9);
  io.github.sjcud.stockagent.rag.retrieval.RetrievalHit second = new io.github.sjcud.stockagent.rag.retrieval.RetrievalHit("b", "收购7亿元", .8);
  java.util.List<io.github.sjcud.stockagent.rag.retrieval.RetrievalHit> sorted = ranker.rerank(new io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery("收购金额", 2), Arrays.asList(first, second));
  assertEquals("b", sorted.get(0).getChunkId()); assertEquals(second.getText(), sorted.get(0).getText());
  assertThrows(IllegalStateException.class, () -> new ModelRetrievalReranker(p -> "[1]").rerank(new io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery("q", 2), Arrays.asList(first, second)));
 }
}
