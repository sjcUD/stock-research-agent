package io.github.sjcud.stockagent.rag.store.qdrant;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.*;

/** 验证 Spring AI 存储边界的条数分批及跨批稳定引用。 */
public class QdrantBatchTest {
    @Test
    public void shouldSplitAndPreserveMetadata() {
        List<Integer> sizes = new ArrayList<Integer>();
        List<Document> captured = new ArrayList<Document>();
        org.springframework.ai.vectorstore.VectorStore delegate =
                (org.springframework.ai.vectorstore.VectorStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {
                                    org.springframework.ai.vectorstore.VectorStore.class
                                },
                                (proxy, method, args) -> {
                                    if ("add".equals(method.getName())) {
                                        List<?> batch = (List<?>) args[0];
                                        sizes.add(batch.size());
                                        for (Object value : batch) captured.add((Document) value);
                                        return null;
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        List<DocumentChunk> chunks = new ArrayList<DocumentChunk>();
        for (int i = 0; i < 5; i++) chunks.add(new DocumentChunk("demo", "v1", 1, i, "片段" + i));
        QdrantVectorStore store = new QdrantVectorStore(delegate, 2);
        store.upsert(chunks);
        assertEquals(Arrays.asList(2, 2, 1), sizes);
        assertEquals("demo:v1:1:4", captured.get(4).getMetadata().get("chunkId"));
        String id = captured.get(0).getId();
        store.upsert(chunks);
        assertEquals(id, captured.get(5).getId());
    }
}
