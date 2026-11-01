package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.github.sjcud.stockagent.research.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.runtime.workspace.WorkspaceRepository;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** 真实HTTP贯通引擎选择、后台任务与归档JSON，不依赖真实供应商或数据库。 */
public class GraphResearchHttpTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test public void shouldSelectGraphThroughHttpAndArchiveActualEngine() throws Exception {
        VectorStore store = new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { }
            public List<RetrievalHit> search(RetrievalQuery query) { return Collections.emptyList(); }
        };
        MultiResearchService research = new MultiResearchService(store, DefaultAnalystFactory.create(p -> "角色报告"), new PortfolioManager(p -> "完整汇总"));
        SpringApplication app = new SpringApplication(StockAgentWebApplication.class);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("graphTestResearch", research));
        try (ServletWebServerApplicationContext context = (ServletWebServerApplicationContext) app.run("--server.port=0", "--server.shutdown=immediate",
                "--stock.agent.chat.api-key=", "--stock.agent.qdrant.enabled=false", "--stock.agent.mysql.enabled=false", "--stock.agent.data.mode=SAMPLE")) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            String multi = "{\"question\":\"问题\",\"symbol\":\"600519\",\"close\":\"1680\",\"asOf\":\"2026-10-30\",\"source\":\"SAMPLE\"}";
            JsonNode legacy = request(base + "/api/research/chat/multi", multi, 200);
            assertEquals("JAVA_EXECUTOR", legacy.path("engine").asText());
            JsonNode graph = request(base + "/api/research/chat/multi", multi.replace("}", ",\"engine\":\"ALIBABA_GRAPH\"}"), 200);
            assertEquals("ALIBABA_GRAPH", graph.path("engine").asText()); assertEquals(5, graph.path("workflowState").path("completedRoles").size());
            String task = "{\"question\":\"趋势\",\"symbol\":\"600519\",\"asOf\":\"2026-10-30\",\"engine\":\"ALIBABA_GRAPH\",\"scope\":\"MARKET\"}";
            JsonNode started = request(base + "/api/research/tasks", task, 202);
            String id = started.path("id").asText(); JsonNode finished = started;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while (!"COMPLETED".equals(finished.path("status").asText()) && System.nanoTime() < until) {
                Thread.sleep(10); finished = request(base + "/api/research/tasks/" + id, null, 200);
                assertNotEquals("FAILED", finished.path("status").asText());
            }
            assertEquals("ALIBABA_GRAPH", finished.path("result").path("research").path("engine").asText());
            WorkspaceRepository.Entry stored = context.getBean(WorkspaceRepository.class).get(id);
            assertEquals("ALIBABA_GRAPH", mapper.readTree(stored.requestJson).path("engine").asText());
            assertEquals("ALIBABA_GRAPH", mapper.readTree(stored.snapshotJson).path("result").path("research").path("engine").asText());
            request(base + "/api/research/tasks", task.replace("MARKET", "DOCUMENTS"), 400);
            request(base + "/api/research/tasks", task.replace("\"ALIBABA_GRAPH\"", "null"), 400);
            request(base + "/api/research/tasks", task.replace("ALIBABA_GRAPH", "UNKNOWN"), 400);
        }
    }
    /** 请求响应真实绑定，超时避免测试HTTP连接无界等待。 */
    private JsonNode request(String uri, String body, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri).openConnection();
        connection.setConnectTimeout(1000); connection.setReadTimeout(5000);
        try {
            if (body != null) { connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                try (java.io.OutputStream stream = connection.getOutputStream()) {stream.write(body.getBytes(StandardCharsets.UTF_8));} }
            assertEquals(expected, connection.getResponseCode());
            try (java.io.InputStream stream = expected >= 400 ? connection.getErrorStream() : connection.getInputStream()) {return mapper.readTree(stream);}
        } finally {connection.disconnect();}
    }
}
