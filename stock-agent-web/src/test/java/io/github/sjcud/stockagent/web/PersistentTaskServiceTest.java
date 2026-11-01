package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.runtime.workspace.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 查询、重启和存储故障不能重新调用模型或把未保存结果显示成成功。 */
public class PersistentTaskServiceTest {
    @Test
    public void shouldRestoreCompletedAnswerWithoutInvokingAction() throws Exception {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        String id, cid;
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            p.onProgress("ANSWER", "", "COMPLETED");
                            return Collections.singletonMap("answer", "持久化回答");
                        },
                        store)) {
            ResearchTaskService.TaskView start = service.start(request());
            id = start.getId();
            cid = start.getConversationId();
            await(service, id);
            assertEquals(2, service.messages(cid).size());
            assertEquals("用户原问题", service.messages(cid).get(0).content);
        }
        AtomicInteger calls = new AtomicInteger();
        try (ResearchTaskService restored =
                new ResearchTaskService(
                        (r, p) -> {
                            calls.incrementAndGet();
                            return "unexpected";
                        },
                        store)) {
            ResearchTaskService.TaskView view = restored.get(id);
            assertEquals("COMPLETED", view.getStatus());
            assertTrue(view.getResult().toString().contains("持久化回答"));
            assertEquals(1, view.getEvents().size());
            assertEquals(1, restored.history(cid).size());
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void shouldRecoverOrphanAsInterruptedWithoutReplay() {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        String cid = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
        store.create(
                new WorkspaceRepository.Conversation(
                        cid, "old", "600519", "2026-10-30", "MARKET", 1, 1),
                new WorkspaceRepository.Entry(
                        id,
                        cid,
                        "q",
                        "{}",
                        "{\"result\":\"must-not-publish\",\"events\":[]}",
                        "RUNNING",
                        1,
                        1));
        AtomicInteger calls = new AtomicInteger();
        try (ResearchTaskService restored =
                new ResearchTaskService(
                        (r, p) -> {
                            calls.incrementAndGet();
                            return "bad";
                        },
                        store)) {
            assertEquals("INTERRUPTED", restored.get(id).getStatus());
            assertNull(restored.get(id).getResult());
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void shouldRejectBeforeSchedulingWhenInitialWriteFails() {
        FaultStore store = new FaultStore();
        store.failCreate = true;
        AtomicInteger calls = new AtomicInteger();
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            calls.incrementAndGet();
                            return "bad";
                        },
                        store)) {
            assertThrows(WorkspaceStorageException.class, () -> service.start(request()));
            assertEquals(0, calls.get());
        }
    }

    @Test
    public void shouldFailInsteadOfPublishingUnsavedSuccess() throws Exception {
        FaultStore store = new FaultStore();
        store.failComplete = true;
        AtomicInteger calls = new AtomicInteger();
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            calls.incrementAndGet();
                            return "不能公开的未保存结果";
                        },
                        store)) {
            ResearchTaskService.TaskView view = await(service, service.start(request()).getId());
            assertEquals("FAILED", view.getStatus());
            assertNull(view.getResult());
            assertTrue(view.getError().contains("保存失败"));
            assertEquals(1, calls.get());
            assertEquals("FAILED", store.get(view.getId()).status);
        }
    }

    /** 队列拒绝且落库失败时仍保留补写者，防止出现永远QUEUED的孤儿。 */
    @Test
    public void shouldRetainRejectedTaskUntilFailureIsSaved() throws Exception {
        FaultStore store = new FaultStore();
        store.failRejected = true;
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            entered.countDown();
                            release.await();
                            return "ok";
                        },
                        store)) {
            service.start(request());
            service.start(request());
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 8; i++) service.start(request());
            assertThrows(
                    java.util.concurrent.RejectedExecutionException.class,
                    () -> service.start(request()));
            assertNotNull(store.rejectedId);
            assertEquals("FAILED", service.get(store.rejectedId).getStatus());
            assertEquals("FAILED", store.get(store.rejectedId).status);
        } finally {
            release.countDown();
        }
    }

    /** 后续角色失败时仍保留此前已经完成的真实轨迹，查询不会再次调用模型。 */
    @Test
    public void shouldKeepCompletedTraceWhenLaterStageFails() throws Exception {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        String id;
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            p.onTrace(
                                    new io.github.sjcud.stockagent.research.AgentTrace(
                                            "FUNDAMENTALS", "actual prompt", "actual output", 12));
                            throw new IllegalStateException("private error");
                        },
                        store)) {
            id = service.start(request()).getId();
            assertEquals(1, await(service, id).getTraces().size());
        }
        try (ResearchTaskService service =
                new ResearchTaskService(
                        (r, p) -> {
                            throw new AssertionError("不应重放");
                        },
                        store)) {
            assertEquals("FAILED", service.get(id).getStatus());
            assertEquals("actual prompt", service.get(id).getTraces().get(0).getInput());
        }
    }

    /** 原问题与含背景的模型输入分开保存。 */
    private ResearchTaskService.TaskRequest request() {
        ResearchTaskService.TaskRequest r = new ResearchTaskService.TaskRequest();
        r.setQuestion("上轮背景及本轮输入");
        r.setDisplayQuestion("用户原问题");
        r.setSymbol("600519");
        r.setAsOf("2026-10-30");
        return r;
    }

    /** 有界等待，避免测试异常造成悬挂。 */
    private ResearchTaskService.TaskView await(ResearchTaskService service, String id)
            throws Exception {
        for (int i = 0; i < 300; i++) {
            ResearchTaskService.TaskView v = service.get(id);
            if (!WorkspaceRepository.active(v.getStatus())) return v;
            Thread.sleep(5);
        }
        throw new AssertionError("任务未结束");
    }

    /** 故障注入使用组合方式，生产仓储无需为了测试开放继承。 */
    private static final class FaultStore implements WorkspaceRepository {
        private final WorkspaceRepository delegate = new InMemoryWorkspaceRepository();
        private boolean failCreate, failComplete, failRejected;
        private String rejectedId;

        public void create(Conversation c, Entry e) {
            if (failCreate) throw new WorkspaceStorageException(null);
            delegate.create(c, e);
        }

        public boolean save(Entry e, String text) {
            if (failRejected && "REJECTED".equals(e.status)) {
                rejectedId = e.id;
                failRejected = false;
                throw new WorkspaceStorageException(null);
            }
            if (failComplete && "COMPLETED".equals(e.status))
                throw new WorkspaceStorageException(null);
            return delegate.save(e, text);
        }

        public Entry get(String id) {
            return delegate.get(id);
        }

        public List<Conversation> conversations(int limit, int offset) {
            return delegate.conversations(limit, offset);
        }

        public List<Entry> entries(String cid) {
            return delegate.entries(cid);
        }

        public List<Message> messages(String cid) {
            return delegate.messages(cid);
        }

        public void interruptUnfinished() {
            delegate.interruptUnfinished();
        }

        public boolean durable() {
            return false;
        }
    }
}
