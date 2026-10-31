package io.github.sjcud.stockagent.rag.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证切片保留顺序、页码和文档 revision。 */
public class FixedWindowChunkingStrategyTest {

    @Test
    public void shouldSplitTextWithoutLosingPageMetadata() {
        FixedWindowChunkingStrategy strategy = new FixedWindowChunkingStrategy(5);
        List<DocumentChunk> chunks = strategy.chunk("doc-1", "rev-1", 3, "abcdefghij");

        assertEquals(2, chunks.size());
        assertEquals("abcde", chunks.get(0).getText());
        assertEquals(3, chunks.get(0).getPage());
        assertEquals("rev-1", chunks.get(1).getDocumentRevision());
        assertTrue(chunks.get(1).getOffset() > chunks.get(0).getOffset());
    }
}
