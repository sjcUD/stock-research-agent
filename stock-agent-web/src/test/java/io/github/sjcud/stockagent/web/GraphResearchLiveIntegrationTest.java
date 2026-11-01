package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.llm.*;
import io.github.sjcud.stockagent.research.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 真实模型+真实StateGraph的6角色验收，使用显式合成行情，不操作Qdrant业务索引。 */
public class GraphResearchLiveIntegrationTest {
    @Test @Timeout(value=3,unit=TimeUnit.MINUTES)
    public void shouldProduceFiveActualReportsAndOneManagerThroughGraph() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("GRAPH_RESEARCH_INTEGRATION")));
        ChatModelProviderConfiguration config = new ChatModelProviderConfiguration();
        ChatModelGateway provider = config.chatModelGateway(config.chatModel(required("STOCK_AGENT_CHAT_API_KEY"), required("STOCK_AGENT_CHAT_BASE_URL"),
                required("STOCK_AGENT_CHAT_MODEL"), 1024, env("STOCK_AGENT_CHAT_THINKING_MODE", "")));
        AtomicInteger calls = new AtomicInteger();
        ChatModelGateway measured = p -> { calls.incrementAndGet(); return provider.chat(p); };
        VectorStore unused = new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { throw new AssertionError("市场研究不入库"); }
            public List<RetrievalHit> search(RetrievalQuery query) { throw new AssertionError("市场研究不混入通用RAG"); }
        };
        MultiResearchService research = new MultiResearchService(unused, DefaultAnalystFactory.create(measured), new PortfolioManager(measured));
        StockResearchData data = new SampleStockProvider().load("600519", "2026-10-30");
        String question = "只做链路验收。每个分析师最多80字：说明SAMPLE、数据日期和证据缺口。经理最多100字。禁止买卖建议。";
        MultiResearchAnswer answer = research.ask(question, data,
                ResearchMode.PARALLEL, ResearchProgressListener.NONE, "", ResearchEngine.ALIBABA_GRAPH);
        assertEquals(ResearchEngine.ALIBABA_GRAPH, answer.getEngine()); assertEquals(6, calls.get());
        assertEquals(5, answer.getDecision().getAnalystResults().size()); assertEquals(6, answer.getTraces().size());
        assertEquals(5, ((List<?>)answer.getWorkflowState().get("completedRoles")).size());
        assertEquals(Boolean.FALSE, answer.getWorkflowState().get("checkpointed"));
        for (AgentTrace trace : answer.getTraces()) assertFalse(trace.getOutput().trim().isEmpty());
        io.github.sjcud.stockagent.runtime.harness.BudgetLedger.Snapshot usage =
                (io.github.sjcud.stockagent.runtime.harness.BudgetLedger.Snapshot) answer.getWorkflowState().get("budget");
        assertEquals(6, usage.settledCalls); assertEquals(0, usage.unknownUsageCalls); assertTrue(usage.actualTokens > 0);
        String evidence = answer.getDecision().getAnalystResults().get(0).getEvidence();
        MultiResearchAnswer partial = research.rerun(question, data.getSnapshot(), evidence, answer.getDecision().getAnalystResults(),
                EnumSet.of(AnalystRole.NEWS), ResearchMode.PARALLEL, ResearchProgressListener.NONE);
        assertEquals(8, calls.get()); assertEquals(4, ((List<?>) partial.getWorkflowState().get("reusedRoles")).size());
        assertEquals(2, ((io.github.sjcud.stockagent.runtime.harness.BudgetLedger.Snapshot)partial.getWorkflowState().get("budget")).settledCalls);
        MultiResearchAnswer managerOnly = research.rerun(question, data.getSnapshot(), evidence, partial.getDecision().getAnalystResults(),
                EnumSet.noneOf(AnalystRole.class), ResearchMode.PARALLEL, ResearchProgressListener.NONE);
        assertEquals(9, calls.get()); assertEquals("REUSE_ONLY", managerOnly.getWorkflowState().get("routeTaken"));
        Path partialReport = Paths.get(System.getProperty("stock.eval.report", "evaluation-reports"), "p8-graph-partial-live.json");
        Files.createDirectories(partialReport.getParent()); new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(partialReport.toFile(), partial);
        Path report = Paths.get(System.getProperty("stock.eval.report", "evaluation-reports"), "p8-graph-live.json");
        Files.createDirectories(report.getParent()); new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), answer);
    }
    private static String required(String name) {String v=System.getenv(name);if(v==null||v.trim().isEmpty())throw new IllegalArgumentException("缺少配置："+name);return v;}
    private static String env(String name,String fallback){String v=System.getenv(name);return v==null||v.trim().isEmpty()?fallback:v;}
}
