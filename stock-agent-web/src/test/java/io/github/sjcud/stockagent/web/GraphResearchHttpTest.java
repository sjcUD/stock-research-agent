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
        java.util.concurrent.atomic.AtomicInteger roleCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger managerCalls = new java.util.concurrent.atomic.AtomicInteger();
        MultiResearchService research = new MultiResearchService(store, DefaultAnalystFactory.create(p -> {roleCalls.incrementAndGet(); return "角色报告";}),
                new PortfolioManager(p -> {managerCalls.incrementAndGet(); return "完整汇总";}));
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
            int rolesBefore = roleCalls.get(), managersBefore = managerCalls.get();
            String originalSnapshot = stored.snapshotJson;
            JsonNode revisionStart = request(base + "/api/research/tasks/" + id + "/rerun", "{\"roles\":[\"NEWS\"]}", 202);
            assertEquals(id, revisionStart.path("sourceTaskId").asText());
            JsonNode revision = await(base, revisionStart.path("id").asText());
            assertEquals(id, revision.path("sourceTaskId").asText());
            assertEquals(id, revision.path("result").path("sourceTaskId").asText());
            assertEquals(rolesBefore + 1, roleCalls.get()); assertEquals(managersBefore + 1, managerCalls.get());
            assertEquals(5, revision.path("result").path("research").path("decision").path("analystResults").size());
            assertEquals(4, revision.path("result").path("research").path("workflowState").path("reusedRoles").size());
            assertEquals(finished.path("result").path("marketData"), revision.path("result").path("marketData"));
            assertEquals(originalSnapshot, context.getBean(WorkspaceRepository.class).get(id).snapshotJson);
            JsonNode managerStart = request(base + "/api/research/tasks/" + id + "/rerun", "{\"roles\":[]}", 202);
            JsonNode managerOnly = await(base, managerStart.path("id").asText());
            assertEquals("REUSE_ONLY", managerOnly.path("result").path("research").path("workflowState").path("routeTaken").asText());
            assertEquals(rolesBefore + 1, roleCalls.get()); assertEquals(managersBefore + 2, managerCalls.get());
            WorkspaceRepository.Entry savedRevision = context.getBean(WorkspaceRepository.class).get(revisionStart.path("id").asText());
            assertEquals(id, mapper.readTree(savedRevision.requestJson).path("rerunSourceId").asText());
            request(base + "/api/research/tasks/" + id + "/rerun", "{\"roles\":[\"NEWS\",\"NEWS\"]}", 400);
            request(base + "/api/research/tasks/" + id + "/rerun", "{\"roles\":[\"NEWS\"],\"question\":\"换题\"}", 400);
            request(base + "/api/research/tasks/" + id + "/rerun", "{}", 400);
            request(base + "/api/research/tasks/unknown/rerun", "{\"roles\":[]}", 404);
            ResearchTaskService.TaskRequest forged = mapper.readValue("{\"rerunSourceId\":\"forged\",\"rerunRoles\":[\"NEWS\"]}", ResearchTaskService.TaskRequest.class);
            assertNull(forged.getRerunSourceId()); assertNull(forged.getRerunRoles());
            request(base + "/api/research/tasks", task.replace("MARKET", "DOCUMENTS"), 400);
            request(base + "/api/research/tasks", task.replace("\"ALIBABA_GRAPH\"", "null"), 400);
            request(base + "/api/research/tasks", task.replace("ALIBABA_GRAPH", "UNKNOWN"), 400);
        }
    }
    /** 真实后台轮询，失败明确抛错，不能把缺失结果当作通过。 */
    private JsonNode await(String base, String id) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < until) {
            JsonNode task = request(base + "/api/research/tasks/" + id, null, 200);
            assertNotEquals("FAILED", task.path("status").asText());
            if ("COMPLETED".equals(task.path("status").asText())) return task;
            Thread.sleep(10);
        }
        throw new AssertionError("重跑任务未结束");
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
