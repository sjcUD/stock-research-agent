package io.github.sjcud.stockagent.rag.parser;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.rag.application.*;
import io.github.sjcud.stockagent.rag.chunking.*;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 页内偏移不能用整个文档偏移替代，空白物理页也不能让后页编号前移。 */
public class PagedDocumentTest {
    @Test
    public void ingestionMustPreservePhysicalPageAndOffset() {
        ParsedDocument doc =
                new ParsedDocument(
                        "d",
                        "v",
                        Arrays.asList(
                                new ParsedPage(1, "FIRST", false),
                                new ParsedPage(2, "", false),
                                new ParsedPage(3, "THIRD", true)),
                        true);
        List<DocumentChunk> actual = new ArrayList<DocumentChunk>();
        VectorStore store =
                new VectorStore() {
                    public void upsert(List<DocumentChunk> chunks) {
                        actual.addAll(chunks);
                    }

                    public List<RetrievalHit> search(RetrievalQuery q) {
                        return Collections.emptyList();
                    }
                };
        IngestionResult result =
                new RagIngestionService(
                                (id, v, bytes, type) -> doc,
                                new FixedWindowChunkingStrategy(100),
                                store)
                        .ingest("d", "v", new byte[] {1}, "application/pdf");
        assertEquals(2, result.getChunkCount());
        assertEquals(3, actual.get(1).getPage());
        assertEquals(0, actual.get(1).getOffset());
        assertEquals("THIRD", actual.get(1).getText());
    }
}
