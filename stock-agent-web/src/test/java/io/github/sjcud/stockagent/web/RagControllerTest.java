package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sjcud.stockagent.rag.application.RagIngestionService;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.chunking.FixedWindowChunkingStrategy;
import io.github.sjcud.stockagent.rag.parser.PlainTextDocumentParser;
import io.github.sjcud.stockagent.rag.retrieval.Citation;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 RAG API 的入库和 Citation 回传边界。 */
public class RagControllerTest {

    @Test
    public void shouldIngestAndSearchWithCitation() {
        FakeVectorStore store = new FakeVectorStore();
        RagController controller = new RagController(
                new RagIngestionService(new PlainTextDocumentParser(),
                        new FixedWindowChunkingStrategy(20), store), store);
        RagController.IngestionRequest request = new RagController.IngestionRequest();
        request.setDocumentId("doc-1");
        request.setRevision("rev-1");
        request.setContent("收入增长");

        RagController.IngestionResponse ingestion = controller.ingest(request);
        RagController.SearchResponse search = controller.search("收入", 1);

        assertEquals(1, ingestion.getChunkCount());
        assertEquals(1, search.getHits().size());
        assertEquals("doc-1", search.getHits().get(0).getCitation().getDocumentId());
    }

    private static class FakeVectorStore implements VectorStore {
        private final List<DocumentChunk> chunks = new ArrayList<DocumentChunk>();

        @Override
        public void upsert(List<DocumentChunk> values) { chunks.addAll(values); }

        @Override
        public List<RetrievalHit> search(RetrievalQuery query) {
            if (chunks.isEmpty()) {
                return Collections.emptyList();
            }
            DocumentChunk chunk = chunks.get(0);
            return Collections.singletonList(new RetrievalHit(chunk.getChunkId(), chunk.getText(), 0.9D,
                    new Citation(chunk.getDocumentId(), chunk.getDocumentRevision(), chunk.getPage(), chunk.getOffset())));
        }
    }
}
