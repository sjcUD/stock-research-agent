package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.sjcud.stockagent.rag.application.RagIngestionService;
import io.github.sjcud.stockagent.rag.chunking.FixedWindowChunkingStrategy;
import io.github.sjcud.stockagent.rag.parser.PlainTextDocumentParser;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.qdrant.QdrantVectorStore;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** 真实模型到 Qdrant 的端到端联调测试。 */
public class RealRagQdrantIntegrationTest {

    @Test
    public void shouldIngestSearchAndReturnCitation() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RAG_REAL_INTEGRATION")));
        String apiKey = System.getenv("STOCK_AGENT_EMBEDDING_API_KEY");
        String baseUrl = System.getenv("STOCK_AGENT_EMBEDDING_BASE_URL");
        String modelName = System.getenv("STOCK_AGENT_EMBEDDING_MODEL");
        int dimensions = Integer.parseInt(System.getenv("STOCK_AGENT_EMBEDDING_DIMENSIONS"));

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .embeddingsPath("/embeddings").build();
        OpenAiEmbeddingOptions options = new OpenAiEmbeddingOptions();
        options.setModel(modelName);
        options.setDimensions(dimensions);
        EmbeddingModel model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options);

        io.qdrant.client.QdrantClient client = new io.qdrant.client.QdrantClient(
                io.qdrant.client.QdrantGrpcClient.newBuilder("localhost", 6334, false).build());
        try {
            org.springframework.ai.vectorstore.qdrant.QdrantVectorStore delegate =
                    org.springframework.ai.vectorstore.qdrant.QdrantVectorStore.builder(client, model)
                            .collectionName("stock_agent_real_rag")
                            .initializeSchema(true)
                            .build();
            delegate.afterPropertiesSet();
            QdrantVectorStore store = new QdrantVectorStore(delegate);
            RagIngestionService ingestion = new RagIngestionService(
                    new PlainTextDocumentParser(), new FixedWindowChunkingStrategy(200), store);

            ingestion.ingestText("real-doc", "rev-1", "贵州茅台本季度收入增长，经营现金流保持稳定。");
            List<RetrievalHit> hits = store.search(new RetrievalQuery("收入增长", 1));

            assertFalse(hits.isEmpty());
            assertNotNull(hits.get(0).getCitation());
        } finally {
            client.close();
        }
    }
}
