package io.github.sjcud.stockagent.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证检索结果可以携带来源引用。 */
public class RetrievalHitTest {

    @Test
    public void shouldExposeCitation() {
        Citation citation = new Citation("doc-1", "rev-2", 3, 128);
        RetrievalHit hit = new RetrievalHit("chunk-1", "净利润增长", 0.91D, citation);

        assertEquals("doc-1", hit.getCitation().getDocumentId());
        assertEquals(3, hit.getCitation().getPage());
    }
}
