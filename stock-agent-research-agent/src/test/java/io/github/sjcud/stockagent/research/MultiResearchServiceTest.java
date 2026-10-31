package io.github.sjcud.stockagent.research;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** 用确定性网关验证角色协作、证据传递、超时和预算。 */
public class MultiResearchServiceTest {
    private final MarketSnapshot snapshot = new MarketSnapshot("600519", "1680", "2026-10-30", "SAMPLE");

    @Test
    public void shouldReturnFiveReportsAndManagerWithEvidenceAndTrace() throws Exception {
        MultiResearchService service = new MultiResearchService(new MemoryStore(),
                DefaultAnalystFactory.create(prompt -> prompt), new PortfolioManager(prompt -> prompt));
        MultiResearchAnswer answer = service.ask("收入与风险如何", snapshot, ResearchMode.PARALLEL);
        assertEquals(5, answer.getDecision().getAnalystResults().size());
        assertEquals(6, answer.getTraces().size());
        assertEquals("doc", answer.getEvidence().get(0).getCitation().getDocumentId());
        for (AgentTrace trace : answer.getTraces()) {
            assertTrue(trace.getInput().contains("收入与风险如何"));
            assertTrue(trace.getInput().contains("c1"));
            assertFalse(trace.getOutput().isEmpty());
            assertTrue(trace.getElapsedMillis() >= 0);
        }
        assertTrue(answer.getDecision().getDecision().contains("缺失"));
        assertThrows(UnsupportedOperationException.class, () -> answer.getTraces().clear());
    }

    @Test
    public void shouldActuallyRunAnalystsConcurrently() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        List<ResearchAnalyst> analysts = Arrays.<ResearchAnalyst>asList(
            new PromptResearchAnalyst(AnalystRole.NEWS, "新闻", prompt -> awaitBoth(entered)),
            new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> awaitBoth(entered)));
        assertEquals(2, new MultiAnalystOrchestrator(analysts).runParallel("问题", snapshot, "证据").size());
    }

    @Test
    public void shouldRejectDuplicateRoles() {
        ResearchAnalyst analyst = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> "结果");
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAnalystOrchestrator(Arrays.asList(analyst, analyst)));
    }

    @Test
    public void shouldStopAtDeadlineAndInterruptWorker() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        ResearchAnalyst slow = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            try { Thread.sleep(10000); } catch (InterruptedException exception) {
                interrupted.countDown(); Thread.currentThread().interrupt();
            }
            return "结果";
        });
        MultiAnalystOrchestrator orchestrator = new MultiAnalystOrchestrator(Collections.singletonList(slow));
        assertThrows(TimeoutException.class, () -> orchestrator.run("问题", snapshot, "证据",
                ResearchMode.PARALLEL, new io.github.sjcud.stockagent.runtime.harness.BudgetLedger(
                    1, System.currentTimeMillis() + 200, 10000)));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void shouldFailBeforeCallingModelWhenBudgetIsEmpty() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ResearchAnalyst analyst = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            calls.incrementAndGet(); return "结果";
        });
        MultiAnalystOrchestrator orchestrator = new MultiAnalystOrchestrator(Collections.singletonList(analyst));
        assertThrows(Exception.class, () -> orchestrator.run("问题", snapshot, "证据",
                ResearchMode.SERIAL, new io.github.sjcud.stockagent.runtime.harness.BudgetLedger(
                    0, System.currentTimeMillis() + 1000, 10000)));
        assertEquals(0, calls.get());
    }

    private String awaitBoth(CountDownLatch entered) {
        entered.countDown();
        try { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException exception) { throw new IllegalStateException(exception); }
        return "已并发进入";
    }

    @Test
    public void shouldNoticeFailedRoleWithoutWaitingForEarlierSlowRole() throws Exception {
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ResearchAnalyst slow = new PromptResearchAnalyst(AnalystRole.NEWS, "新闻", prompt -> {
            slowEntered.countDown();
            try { Thread.sleep(10000); }
            catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return "新闻";
        });
        ResearchAnalyst failed = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            try { slowEntered.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("模型不可用");
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> future = caller.submit(() -> {
            assertThrows(ExecutionException.class, () -> new MultiAnalystOrchestrator(Arrays.asList(slow, failed))
                    .runParallel("问题", snapshot, "证据"));
        });
        try {
            future.get(2, TimeUnit.SECONDS);
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        } finally { future.cancel(true); caller.shutdownNow(); }
    }

    @Test
    public void shouldNotInvokeManagerIfAnAnalystFails() {
        MultiResearchService service = new MultiResearchService(new MemoryStore(),
                DefaultAnalystFactory.create(prompt -> { throw new IllegalStateException("失败"); }),
                new PortfolioManager(prompt -> { fail("不能汇总失败报告"); return ""; }));
        assertThrows(ExecutionException.class, () -> service.ask("问题", snapshot, ResearchMode.SERIAL));
    }

    @Test
    public void shouldRejectInsufficientTokenBudgetBeforeModelInvocation() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ResearchAnalyst analyst = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            calls.incrementAndGet(); return "结果";
        });
        assertThrows(ExecutionException.class, () -> new MultiAnalystOrchestrator(Collections.singletonList(analyst))
                .run("问题", snapshot, "证据", ResearchMode.PARALLEL,
                        new io.github.sjcud.stockagent.runtime.harness.BudgetLedger(
                                1, System.currentTimeMillis() + 1000, 0)));
        assertEquals(0, calls.get());
    }

    @Test
    public void shouldIncludeSnapshotTextInTokenReservation() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String longSource = String.join("", Collections.nCopies(10000, "源"));
        MarketSnapshot large = new MarketSnapshot("600519", "1680", "2026-10-30", longSource);
        ResearchAnalyst analyst = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            calls.incrementAndGet(); return "结果";
        });
        assertThrows(ExecutionException.class, () -> new MultiAnalystOrchestrator(Collections.singletonList(analyst))
                .run("问题", large, "证据", ResearchMode.PARALLEL,
                        new io.github.sjcud.stockagent.runtime.harness.BudgetLedger(
                                1, System.currentTimeMillis() + 1000, 5000)));
        assertEquals(0, calls.get());
    }


    @Test
    public void shouldNotStartLaterRolesAfterSerialFailure() {
        java.util.concurrent.atomic.AtomicInteger laterCalls = new java.util.concurrent.atomic.AtomicInteger();
        ResearchAnalyst first = new PromptResearchAnalyst(AnalystRole.NEWS, "新闻", prompt -> {
            throw new IllegalStateException("首个角色失败");
        });
        ResearchAnalyst later = new PromptResearchAnalyst(AnalystRole.RISK, "风险", prompt -> {
            laterCalls.incrementAndGet(); return "结果";
        });
        assertThrows(IllegalStateException.class, () -> new MultiAnalystOrchestrator(Arrays.asList(first, later))
                .runSerial("问题", snapshot, "证据"));
        assertEquals(0, laterCalls.get());
    }

    private static class MemoryStore implements VectorStore {
        @Override public void upsert(List<DocumentChunk> chunks) { }
        @Override public List<RetrievalHit> search(RetrievalQuery query) {
            return Collections.singletonList(new RetrievalHit("c1", "样例收入证据", 0.9,
                    new Citation("doc", "v1", 1, 0)));
        }
    }
}
