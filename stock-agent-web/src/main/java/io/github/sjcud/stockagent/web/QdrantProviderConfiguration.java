package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.rag.application.RagIngestionService;
import io.github.sjcud.stockagent.rag.chunking.FixedWindowChunkingStrategy;
import io.github.sjcud.stockagent.rag.parser.DocumentParser;
import io.github.sjcud.stockagent.rag.parser.PlainTextDocumentParser;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.rag.store.qdrant.QdrantVectorStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant 与 RAG 入库链路装配。
 *
 * <p>真实 EmbeddingModel 由 EmbeddingProviderConfiguration 提供；Qdrant
 * 只负责保存和检索，核心 RAG 服务不依赖客户端配置细节。</p>
 */
@Configuration
@ConditionalOnProperty(name = "stock.agent.qdrant.enabled", havingValue = "true")
public class QdrantProviderConfiguration {

    /** 创建非 TLS 的本地或内网 Qdrant gRPC 客户端。 */
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(
            @Value("${stock.agent.qdrant.host:localhost}") String host,
            @Value("${stock.agent.qdrant.grpc-port:6334}") int port,
            @Value("${stock.agent.qdrant.api-key:}") String apiKey) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, false)
                .withTimeout(Duration.ofSeconds(10));
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    /** 创建 Spring AI Qdrant VectorStore，并显式初始化 collection。 */
    @Bean
    public org.springframework.ai.vectorstore.qdrant.QdrantVectorStore qdrantDelegate(
            QdrantClient client,
            EmbeddingModel embeddingModel,
            @Value("${stock.agent.qdrant.collection:stock_agent_documents}") String collection)
            throws Exception {
        org.springframework.ai.vectorstore.qdrant.QdrantVectorStore store =
                org.springframework.ai.vectorstore.qdrant.QdrantVectorStore.builder(client, embeddingModel)
                        .collectionName(collection)
                        .initializeSchema(true)
                        .build();
        store.afterPropertiesSet();
        return store;
    }

    /** 将供应商无关的 VectorStore 暴露给 RAG 应用服务。 */
    @Bean
    public VectorStore vectorStore(org.springframework.ai.vectorstore.qdrant.QdrantVectorStore delegate) {
        return new QdrantVectorStore(delegate);
    }

    /** 创建首版纯文本解析器。 */
    @Bean
    public DocumentParser documentParser() {
        return new PlainTextDocumentParser();
    }

    /** 创建确定性的固定窗口切片策略。 */
    @Bean
    public FixedWindowChunkingStrategy chunkingStrategy(
            @Value("${stock.agent.rag.chunk-size:800}") int chunkSize) {
        return new FixedWindowChunkingStrategy(chunkSize);
    }

    /** 装配真实 Embedding → Qdrant 的文档入库服务。 */
    @Bean
    public RagIngestionService ragIngestionService(DocumentParser parser,
                                                    FixedWindowChunkingStrategy chunkingStrategy,
                                                    VectorStore vectorStore) {
        return new RagIngestionService(parser, chunkingStrategy, vectorStore);
    }
}
