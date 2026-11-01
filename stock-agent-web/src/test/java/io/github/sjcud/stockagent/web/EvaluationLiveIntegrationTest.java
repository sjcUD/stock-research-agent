package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.evaluation.*;
import io.github.sjcud.stockagent.llm.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.rag.store.qdrant.QdrantVectorStore;
import io.github.sjcud.stockagent.research.*;
import io.qdrant.client.*;

import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 真实评测显式启用；仅创建并删除 UUID 测试 collection，绝不清理用户 collection。 */
public class EvaluationLiveIntegrationTest {
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    public void shouldCompareEmbeddingsAndCheckGroundedChat() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("EVALUATION_INTEGRATION")));
        EvaluationDataset data = EvaluationDataset.load();
        EvaluationSuite suite = new EvaluationSuite(data);
        String[] models =
                setting(System.getenv(), "EVAL_EMBEDDING_MODELS", "STOCK_AGENT_EMBEDDING_MODEL")
                        .split(",");
        String[] dimensions =
                setting(
                                System.getenv(),
                                "EVAL_EMBEDDING_DIMENSIONS",
                                "STOCK_AGENT_EMBEDDING_DIMENSIONS")
                        .split(",");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> checks = new LinkedHashMap<String, Object>();
        int failures = 0;
        EmbeddingModel primary = null;
        String primaryName = null;
        int primaryDimensions = 0;
        // 显式组合模型与维度；每组重建独立索引，禁止混用旧空间。错误留在报告，不静默跳过。
        for (String modelName : models)
            for (String dimension : dimensions) {
                int size = Integer.parseInt(dimension.trim());
                try {
                    EmbeddingModel model =
                            new EmbeddingProviderConfiguration()
                                    .embeddingModel(
                                            required("STOCK_AGENT_EMBEDDING_API_KEY"),
                                            required("STOCK_AGENT_EMBEDDING_BASE_URL"),
                                            modelName.trim(),
                                            size);
                    EmbeddingGateway gateway =
                            EvaluationSuite.cached(new SpringAiEmbeddingGateway(model));
                    for (int chunk : new int[] {32, 64, 128}) {
                        VectorStore index = new EvaluationVectorIndex(gateway, size);
                        index.upsert(suite.chunks(chunk));
                        for (int k : new int[] {1, 3, 5})
                            rows.add(suite.evaluate(index, modelName.trim(), size, chunk, k));
                    }
                    if (primary == null) {
                        primary = model;
                        primaryName = modelName.trim();
                        primaryDimensions = size;
                    }
                } catch (RuntimeException ex) {
                    failures++;
                    Map<String, Object> error = new LinkedHashMap<String, Object>();
                    error.put("model", modelName.trim());
                    error.put("dimensions", size);
                    error.put("errors", 1);
                    error.put("errorType", ex.getClass().getSimpleName());
                    rows.add(error);
                }
            }
        if (primary != null) {
            try {
                String collection = "stock_eval_" + UUID.randomUUID().toString().replace("-", "");
                QdrantClient client =
                        new QdrantClient(
                                QdrantGrpcClient.newBuilder(
                                                env("EVAL_QDRANT_HOST", "localhost"),
                                                Integer.parseInt(env("EVAL_QDRANT_PORT", "6334")),
                                                false)
                                        .build());
                try {
                    org.springframework.ai.vectorstore.qdrant.QdrantVectorStore delegate =
                            org.springframework.ai.vectorstore.qdrant.QdrantVectorStore.builder(
                                            client, primary)
                                    .collectionName(collection)
                                    .initializeSchema(true)
                                    .build();
                    delegate.afterPropertiesSet();
                    VectorStore store = new QdrantVectorStore(delegate);
                    store.upsert(suite.chunks(64));
                    checks.put(
                            "qdrant", suite.evaluate(store, primaryName, primaryDimensions, 64, 3));
                    ChatModelProviderConfiguration config = new ChatModelProviderConfiguration();
                    ChatModelGateway chat =
                            config.chatModelGateway(
                                    config.chatModel(
                                            required("STOCK_AGENT_CHAT_API_KEY"),
                                            required("STOCK_AGENT_CHAT_BASE_URL"),
                                            required("STOCK_AGENT_CHAT_MODEL"),
                                            2048,
                                            env("STOCK_AGENT_CHAT_THINKING_MODE", "")));
                    ResearchChatService service =
                            new ResearchChatService(store, new FundamentalsAnalyst(chat));
                    List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
                    for (int i : new int[] {0, 5, 10, 12}) {
                        EvaluationDataset.Question q = data.getQuestions().get(i);
                        ResearchChatAnswer answer =
                                service.ask(
                                        q.getText(),
                                        new MarketSnapshot(
                                                "SYNTHETIC", "0", "2025-12-31", "EVALUATION"));
                        Set<String> ids = new HashSet<String>();
                        RagEvaluator sourceEvaluator = new RagEvaluator(data);
                        boolean sourcesComplete = !answer.getEvidence().isEmpty();
                        for (io.github.sjcud.stockagent.rag.retrieval.RetrievalHit hit :
                                answer.getEvidence()) {
                            if (sourceEvaluator.validCitation(hit)) ids.add(hit.getChunkId());
                            else sourcesComplete = false;
                        }
                        if (!sourcesComplete) failures++;
                        AnswerEvaluator.Score score =
                                new AnswerEvaluator()
                                        .score(
                                                answer.getAnswer(),
                                                q.getFacts(),
                                                q.getRelevant().isEmpty(),
                                                ids);
                        Map<String, Object> detail = new LinkedHashMap<String, Object>();
                        detail.put("id", q.getId());
                        detail.put("answer", answer.getAnswer());
                        detail.put("rules", score);
                        detail.put("sourcesComplete", sourcesComplete);
                        answers.add(detail);
                        if (!score.passed()) failures++;
                    }
                    checks.put("chatModel", required("STOCK_AGENT_CHAT_MODEL"));
                    checks.put("answerRules", answers);
                } finally {
                    // collection 名称在本方法生成，删除范围不能由环境变量指定。
                    try {
                        client.deleteCollectionAsync(collection).get(10, TimeUnit.SECONDS);
                    } finally {
                        client.close();
                    }
                }
            } catch (Exception ex) {
                failures++;
                checks.put("integrationErrorType", ex.getClass().getSimpleName());
            }
        }
        checks.put("embeddingBatchSize", 10);
        checks.put("embeddingTextCache", true);
        suite.writeReport(
                Paths.get(System.getProperty("stock.eval.report", "evaluation-reports")),
                "live",
                rows,
                checks);
        for (Map<String, Object> row : rows)
            assertEquals(0, ((Number) row.get("errors")).intValue(), "Embedding 配置评测失败，详见脱敏报告");
        assertTrue(checks.containsKey("qdrant"), "必须产生真实 Qdrant 评测结果");
        assertTrue(completeSources((Map<?, ?>) checks.get("qdrant")), "Qdrant 引用缺失或不可回链");
        assertNotNull(primary, "至少一个真实模型配置必须成功");
        assertEquals(0, failures, "真实模型规则检查或配置失败，详见 live.json；规则通过不代表语义质量满分");
    }

    /** 延迟读取回退配置，显式实验覆盖值不应依赖未使用的生产默认。 */
    static String setting(Map<String, String> environment, String overrideKey, String fallbackKey) {
        String value = environment.get(overrideKey);
        if (value == null || value.trim().isEmpty()) value = environment.get(fallbackKey);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("缺少评测配置 " + overrideKey);
        return value;
    }

    /** 新建固定语料索引必须有命中且所有片段可回链；空命中的 coverage 为 null。 */
    static boolean completeSources(Map<?, ?> row) {
        return row != null
                && Integer.valueOf(0).equals(row.get("errors"))
                && Double.valueOf(1.0).equals(row.get("citationCoverage"));
    }

    /** 不回显配置值，防止缺失配置异常泄露密钥。 */
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("缺少配置 " + key);
        return value;
    }

    /** 仅为非敏感参数提供默认值。 */
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
