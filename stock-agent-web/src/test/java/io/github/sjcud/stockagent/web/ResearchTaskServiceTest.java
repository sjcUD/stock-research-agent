package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** 验证任务真实状态、可取消性及取消后迟到结果不会重新出现。 */
public class ResearchTaskServiceTest {
    @Test
    public void shouldPublishProgressAndCompleteWithResult() throws Exception {
        try (ResearchTaskService service = new ResearchTaskService((request,progress) -> {
            progress.onProgress("DATA","","COMPLETED"); return "真实结果";
        })) {
            String id = service.start(request()).getId();
            ResearchTaskService.TaskView view = awaitTerminal(service,id);
            assertEquals("COMPLETED",view.getStatus()); assertEquals("真实结果",view.getResult());
            assertTrue(view.getEvents().stream().anyMatch(event -> "DATA".equals(event.getPhase())));
            assertThrows(UnsupportedOperationException.class, () -> view.getEvents().clear());
        }
    }
    @Test
    public void shouldInterruptAndDiscardLateResultAfterCancel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        try (ResearchTaskService service = new ResearchTaskService((request,progress) -> {
            entered.countDown(); try { Thread.sleep(10000); }
            catch (InterruptedException exception) { interrupted.countDown(); }
            return "迟到结果";
        })) {
            String id=service.start(request()).getId(); assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("CANCELLED",service.cancel(id).getStatus());
            assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertNull(service.get(id).getResult()); assertEquals("CANCELLED",service.get(id).getStatus());
        }
    }
    @Test
    public void shouldSanitizeFailureWithoutExposingProviderMessage() throws Exception {
        try (ResearchTaskService service = new ResearchTaskService((request,progress) -> {
            throw new IllegalStateException("private-provider-secret");
        })) {
            ResearchTaskService.TaskView view = awaitTerminal(service,service.start(request()).getId());
            assertEquals("FAILED",view.getStatus()); assertFalse(view.getError().contains("private"));
            assertNull(view.getResult());
        }
    }
    @Test
    public void shouldReleaseQueueSlotWhenQueuedTaskIsCancelled() throws Exception {
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        try (ResearchTaskService service=new ResearchTaskService((request,progress) -> {
            entered.countDown(); release.await(); return "完成";
        })) {
            service.start(request()); service.start(request()); assertTrue(entered.await(2,TimeUnit.SECONDS));
            java.util.List<String> queued=new java.util.ArrayList<String>();
            for(int index=0;index<8;index++)queued.add(service.start(request()).getId());
            assertThrows(RejectedExecutionException.class,()->service.start(request()));
            service.cancel(queued.get(0));
            assertNotNull(service.start(request()).getId());
        } finally { release.countDown(); }
    }
    @Test
    public void shouldAnswerDocumentsWithoutFetchingMarketPrices() throws Exception {
        org.springframework.beans.factory.support.StaticListableBeanFactory beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        io.github.sjcud.stockagent.rag.store.VectorStore store=new io.github.sjcud.stockagent.rag.store.VectorStore() {
            public void upsert(java.util.List<io.github.sjcud.stockagent.rag.chunking.DocumentChunk> chunks) { }
            public java.util.List<io.github.sjcud.stockagent.rag.retrieval.RetrievalHit> search(io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery query) { return java.util.Collections.emptyList(); }
        };
        beans.addBean("documents",new io.github.sjcud.stockagent.research.ResearchChatService(store,
                new io.github.sjcud.stockagent.analyst.FundamentalsAnalyst(prompt->prompt)));
        io.github.sjcud.stockagent.data.StockDataProvider unavailable=(symbol,date)->{throw new AssertionError("资料问答不应调用行情");};
        try(ResearchTaskService service=new WorkspaceConfiguration().researchTaskService(unavailable,
                beans.getBeanProvider(io.github.sjcud.stockagent.research.MultiResearchService.class),
                beans.getBeanProvider(io.github.sjcud.stockagent.research.ResearchChatService.class))) {
            ResearchTaskService.TaskRequest request=request();request.setScope(ResearchTaskService.Scope.DOCUMENTS);
            request.setQuestion("DOC_QUESTION_UNIQUE");
            ResearchTaskService.TaskView view=awaitTerminal(service,service.start(request).getId());
            assertEquals("COMPLETED",view.getStatus());
            io.github.sjcud.stockagent.research.ResearchChatAnswer answer=(io.github.sjcud.stockagent.research.ResearchChatAnswer)view.getResult();
            assertTrue(answer.getAnswer().contains("DOC_QUESTION_UNIQUE"));
            assertTrue(answer.getAnswer().contains("DOCUMENT_CONTEXT_ONLY"));
        }
    }
    private ResearchTaskService.TaskRequest request() {
        ResearchTaskService.TaskRequest request = new ResearchTaskService.TaskRequest();
        request.setQuestion("分析趋势"); request.setSymbol("600519"); request.setAsOf("2026-10-30"); return request;
    }
    private ResearchTaskService.TaskView awaitTerminal(ResearchTaskService service,String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime()<until) {
            ResearchTaskService.TaskView view=service.get(id);
            if ("COMPLETED".equals(view.getStatus()) || "FAILED".equals(view.getStatus())) { return view; }
            Thread.sleep(5);
        }
        throw new AssertionError("任务未结束");
    }
}
