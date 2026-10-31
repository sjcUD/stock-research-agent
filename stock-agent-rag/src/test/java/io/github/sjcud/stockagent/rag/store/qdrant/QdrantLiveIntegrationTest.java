package io.github.sjcud.stockagent.rag.store.qdrant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 真实 Qdrant 写入和检索测试。
 *
 * <p>只有设置 {@code QDRANT_INTEGRATION=true} 时运行，避免普通单元测试
 * 必须依赖本地 Docker；向量模型使用确定性测试模型，验证的是网络和存储链路。</p>
 */
public class QdrantLiveIntegrationTest {

    @Test
    public void shouldWriteAndSearchAgainstQdrant() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("QDRANT_INTEGRATION")));

        io.qdrant.client.QdrantGrpcClient grpcClient = io.qdrant.client.QdrantGrpcClient
                .newBuilder("localhost", 6334, false)
                .build();
        io.qdrant.client.QdrantClient client = new io.qdrant.client.QdrantClient(grpcClient);
        try {
            EmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
            org.springframework.ai.vectorstore.qdrant.QdrantVectorStore delegate =
                    org.springframework.ai.vectorstore.qdrant.QdrantVectorStore
                            .builder(client, embeddingModel)
                            .collectionName("stock_agent_integration")
                            .initializeSchema(true)
                            .build();
            delegate.afterPropertiesSet();
            QdrantVectorStore store = new QdrantVectorStore(delegate);

            store.upsert(Collections.singletonList(new DocumentChunk(
                    "doc-live", "rev-1", 1, 0, "贵州茅台收入增长")));
            List<RetrievalHit> hits = store.search(new RetrievalQuery("收入增长", 1));

            assertFalse(hits.isEmpty());
            assertTrue(hits.get(0).getText().contains("收入增长"));
            assertTrue(hits.get(0).getCitation() != null);
        } finally {
            client.close();
        }
    }

    /** 固定三维向量，使联调不依赖外部 Embedding API。 */
    private static class DeterministicEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            java.util.List<org.springframework.ai.embedding.Embedding> results =
                    new java.util.ArrayList<org.springframework.ai.embedding.Embedding>();
            int index = 0;
            for (String text : request.getInstructions()) {
                results.add(new org.springframework.ai.embedding.Embedding(vector(text), index));
                index++;
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return Arrays.asList(vector(texts.get(0)));
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        @Override
        public int dimensions() {
            return 3;
        }

        private float[] vector(String text) {
            return new float[] {text.contains("收入") ? 1.0F : 0.0F,
                    text.contains("增长") ? 1.0F : 0.0F, 0.1F};
        }
    }
}
