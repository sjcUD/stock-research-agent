package io.github.sjcud.stockagent.rag.store;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证低分拒答、阈值边界和同分排序稳定性。 */
public class ThresholdRerankingVectorStoreTest {
    @Test public void shouldFilterInclusiveThresholdAndKeepTopK() {
        VectorStore base = new InMemoryStore(Arrays.asList(
                new RetrievalHit("z", "z", .8), new RetrievalHit("a", "a", .8),
                new RetrievalHit("low", "low", .2)));
        ThresholdRerankingVectorStore store = new ThresholdRerankingVectorStore(base, .8,
                new ScoreRetrievalReranker());
        List<RetrievalHit> hits = store.search(new RetrievalQuery("q", 1));
        assertEquals(1, hits.size()); assertEquals("a", hits.get(0).getChunkId());
    }
    @Test public void shouldForwardUpsertToDelegate() {
        InMemoryStore base = new InMemoryStore(Collections.<RetrievalHit>emptyList());
        ThresholdRerankingVectorStore store = new ThresholdRerankingVectorStore(base, 0,
                new ScoreRetrievalReranker());
        store.upsert(Collections.singletonList(new DocumentChunk("d", "v1", 1, 0, "text")));
        assertTrue(base.upserted);
    }
    private static final class InMemoryStore implements VectorStore {
        private final List<RetrievalHit> hits; private boolean upserted;
        private InMemoryStore(List<RetrievalHit> hits) { this.hits = hits; }
        @Override public void upsert(List<DocumentChunk> chunks) { upserted = true; }
        @Override public List<RetrievalHit> search(RetrievalQuery query) { return hits; }
    }
}
