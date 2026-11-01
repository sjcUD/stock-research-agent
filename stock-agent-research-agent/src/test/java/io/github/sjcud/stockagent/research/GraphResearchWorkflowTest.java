package io.github.sjcud.stockagent.research;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 使用真实StateGraph和脚本网关验证状态汇聚，禁止用模拟图替代框架编排。 */
public class GraphResearchWorkflowTest {
    private final MarketSnapshot snapshot = new MarketSnapshot("600519", "16.80", "2026-10-30", "SAMPLE");
    @Test public void shouldGatherEveryRoleAndInvokeManagerExactlyOnce() throws Exception {
        for (ResearchMode mode : ResearchMode.values()) {
            AtomicInteger managerCalls = new AtomicInteger();
            ResearchWorkflow graph = new GraphResearchWorkflow(DefaultAnalystFactory.create(p -> "角色报告"),
                    new PortfolioManager(p -> { managerCalls.incrementAndGet(); return "经理汇总"; }));
            WorkflowResult result = graph.execute("问题", snapshot, "证据", mode,
                    new BudgetLedger(6, System.currentTimeMillis() + 5000, 60000), ResearchProgressListener.NONE);
            assertEquals(5, result.getDecision().getAnalystResults().size());
            assertEquals(1, managerCalls.get()); assertEquals(5, ((List<?>) result.getState().get("completedRoles")).size());
            assertEquals(Boolean.TRUE, result.getState().get("decisionProduced"));
        }
    }
    @Test public void parallelNodesMustActuallyOverlap() throws Exception {
        CountDownLatch both = new CountDownLatch(2);
        List<ResearchAnalyst> roles = Arrays.asList(analyst(AnalystRole.NEWS, p -> awaitBoth(both)), analyst(AnalystRole.RISK, p -> awaitBoth(both)));
        WorkflowResult result = new GraphResearchWorkflow(roles, new PortfolioManager(p -> "ok")).execute("q", snapshot, "e", ResearchMode.PARALLEL,
                new BudgetLedger(3, System.currentTimeMillis() + 5000, 60000), ResearchProgressListener.NONE);
        assertEquals(2, result.getDecision().getAnalystResults().size());
    }
    @Test public void roleFailureMustCancelSiblingAndSkipManager() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicInteger manager = new AtomicInteger();
        List<ResearchAnalyst> roles = Arrays.asList(analyst(AnalystRole.NEWS, p -> {
            slowStarted.countDown(); try { Thread.sleep(10000); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); } return "slow";
        }), analyst(AnalystRole.RISK, p -> { try { assertTrue(slowStarted.await(1, TimeUnit.SECONDS)); } catch (InterruptedException ex) { throw new IllegalStateException(ex); } throw new IllegalStateException("expected"); }));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> run = caller.submit(() -> assertThrows(Exception.class, () -> new GraphResearchWorkflow(roles,
                    new PortfolioManager(p -> {manager.incrementAndGet();return "bad";})).execute("q", snapshot, "e", ResearchMode.PARALLEL,
                    new BudgetLedger(3, System.currentTimeMillis() + 5000, 60000), ResearchProgressListener.NONE)));
            run.get(2, TimeUnit.SECONDS); assertTrue(interrupted.await(1, TimeUnit.SECONDS)); assertEquals(0, manager.get());
        } finally { caller.shutdownNow(); }
    }
    @Test public void budgetMustRejectBeforeAnyModelInvocation() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(Exception.class, () -> new GraphResearchWorkflow(Arrays.asList(analyst(AnalystRole.RISK, p -> {calls.incrementAndGet(); return "bad";})),
                new PortfolioManager(p -> "bad")).execute("q", snapshot, "e", ResearchMode.SERIAL,
                new BudgetLedger(2, System.currentTimeMillis()+3000, 1), ResearchProgressListener.NONE));
        assertEquals(0, calls.get());
    }
    @Test public void shouldInterruptModelAtDeadline() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        ResearchAnalyst slow = analyst(AnalystRole.RISK, p -> { try { Thread.sleep(10000); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); } return "late"; });
        assertThrows(TimeoutException.class, () -> new GraphResearchWorkflow(Arrays.asList(slow), new PortfolioManager(p -> "bad"))
                .execute("q", snapshot, "e", ResearchMode.SERIAL, new BudgetLedger(2, System.currentTimeMillis()+1000, 60000), ResearchProgressListener.NONE));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
    @Test public void userCancellationMustInterruptNestedGraphModel() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        ResearchAnalyst slow = analyst(AnalystRole.RISK, p -> { started.countDown();try { Thread.sleep(10000); } catch (InterruptedException ex) {interrupted.countDown();Thread.currentThread().interrupt();}return "late"; });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = caller.submit(() -> {
                try { new GraphResearchWorkflow(Arrays.asList(slow), new PortfolioManager(p -> "bad")).execute("q", snapshot, "e", ResearchMode.SERIAL,
                        new BudgetLedger(2, System.currentTimeMillis()+5000, 60000), ResearchProgressListener.NONE); }
                catch (Exception expected) { }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));future.cancel(true);assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally {caller.shutdownNow();}
    }
    @Test public void serviceMustExposeEngineAndKeepRequestsIsolated() throws Exception {
        io.github.sjcud.stockagent.rag.store.VectorStore store = new io.github.sjcud.stockagent.rag.store.VectorStore() {
            public void upsert(List<io.github.sjcud.stockagent.rag.chunking.DocumentChunk> values) { }
            public List<io.github.sjcud.stockagent.rag.retrieval.RetrievalHit> search(io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery q) { return Collections.emptyList(); }
        };
        MultiResearchService service = new MultiResearchService(store, DefaultAnalystFactory.create(p -> p), new PortfolioManager(p -> "manager"));
        MultiResearchAnswer java = service.ask("FIRST", snapshot, ResearchMode.SERIAL);
        MultiResearchAnswer first = service.ask("FIRST", snapshot, ResearchMode.SERIAL, ResearchEngine.ALIBABA_GRAPH);
        MultiResearchAnswer second = service.ask("SECOND", snapshot, ResearchMode.PARALLEL, ResearchEngine.ALIBABA_GRAPH);
        assertEquals(ResearchEngine.JAVA_EXECUTOR, java.getEngine());assertEquals(ResearchEngine.ALIBABA_GRAPH, first.getEngine());
        assertEquals(java.getDecision().getAnalystResults().get(0).getConclusion(), first.getDecision().getAnalystResults().get(0).getConclusion());
        assertTrue(second.getTraces().get(0).getOutput().contains("SECOND"));assertFalse(second.getTraces().get(0).getOutput().contains("FIRST"));
    }
    private ResearchAnalyst analyst(AnalystRole role, io.github.sjcud.stockagent.llm.ChatModelGateway model) { return new PromptResearchAnalyst(role, role.name(), model); }
    private String awaitBoth(CountDownLatch latch) { latch.countDown();try { assertTrue(latch.await(2, TimeUnit.SECONDS)); return "report"; } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);} }
}
