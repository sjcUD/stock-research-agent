package io.github.sjcud.stockagent.rag.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.chunking.FixedWindowChunkingStrategy;
import io.github.sjcud.stockagent.rag.parser.PlainTextDocumentParser;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证解析、切片和向量存储编排的边界。 */
public class RagIngestionServiceTest {

    @Test
    public void shouldParseChunkAndUpsert() {
        CapturingVectorStore store = new CapturingVectorStore();
        RagIngestionService service = new RagIngestionService(
                new PlainTextDocumentParser(), new FixedWindowChunkingStrategy(4), store);

        IngestionResult result = service.ingest(
                "公告-001", "rev-1", "abcdefghijklmnop".getBytes(StandardCharsets.UTF_8), "text/plain");

        assertEquals(4, result.getChunkCount());
        assertEquals(4, store.chunks.size());
        assertEquals("公告-001", store.chunks.get(0).getDocumentId());
        assertEquals("公告-001:rev-1:1:0", store.chunks.get(0).getChunkId());
    }

    private static class CapturingVectorStore implements VectorStore {
        private final List<DocumentChunk> chunks = new ArrayList<DocumentChunk>();

        @Override
        public void upsert(List<DocumentChunk> values) {
            chunks.addAll(values);
        }

        @Override
        public List<RetrievalHit> search(RetrievalQuery query) {
            return new ArrayList<RetrievalHit>();
        }
    }
}
