package io.github.sjcud.stockagent.rag.store;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证检索超时和取消会中断等待并释放执行线程。 */
public class TimedVectorStoreTest {

    @Test
    public void shouldCancelBeforeSubmittingSearch() {
        TimedVectorStore store = new TimedVectorStore(new SlowStore());

        assertThrows(IllegalStateException.class, () -> store.search(
                new RetrievalQuery("收入", 1), 1000L, () -> true));
    }

    private static class SlowStore implements VectorStore {
        @Override public void upsert(List<DocumentChunk> chunks) { }
        @Override public List<RetrievalHit> search(RetrievalQuery query) {
            return new ArrayList<RetrievalHit>();
        }
    }
}
