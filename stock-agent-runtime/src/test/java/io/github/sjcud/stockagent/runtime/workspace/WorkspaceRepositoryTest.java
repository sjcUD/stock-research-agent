package io.github.sjcud.stockagent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 仓储必须保持消息原子性、终态不可回退以及重启中断语义。 */
public class WorkspaceRepositoryTest {
    @Test
    public void shouldPersistTurnAndPreventTerminalOverwrite() {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        WorkspaceRepository.Conversation c =
                new WorkspaceRepository.Conversation(
                        "c", "title", "600519", "2026-10-30", "MARKET", 1, 1);
        WorkspaceRepository.Entry initial =
                new WorkspaceRepository.Entry("t", "c", "问题", "{}", "{}", "QUEUED", 1, 1);
        store.create(c, initial);
        assertEquals(2, store.messages("c").size());
        assertTrue(
                store.save(
                        new WorkspaceRepository.Entry(
                                "t", "c", "问题", "{}", "{\"result\":\"answer\"}", "COMPLETED", 1, 2),
                        "answer"));
        assertFalse(store.save(initial, "late"));
        assertEquals("COMPLETED", store.get("t").status);
        assertEquals("answer", store.messages("c").get(1).content);
    }

    @Test
    public void shouldMarkOnlyUnfinishedWorkInterrupted() {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        WorkspaceRepository.Conversation c =
                new WorkspaceRepository.Conversation(
                        "c", "title", "600519", "2026-10-30", "MARKET", 1, 1);
        store.create(c, new WorkspaceRepository.Entry("a", "c", "q", "{}", "{}", "RUNNING", 1, 1));
        store.create(c, new WorkspaceRepository.Entry("b", "c", "q", "{}", "{}", "QUEUED", 1, 2));
        store.save(
                new WorkspaceRepository.Entry("b", "c", "q", "{}", "{}", "COMPLETED", 1, 3),
                "done");
        store.interruptUnfinished();
        assertEquals("INTERRUPTED", store.get("a").status);
        assertEquals("COMPLETED", store.get("b").status);
    }

    @Test
    public void shouldRejectConversationContextMismatch() {
        WorkspaceRepository store = new InMemoryWorkspaceRepository();
        store.create(
                new WorkspaceRepository.Conversation(
                        "c", "a", "600519", "2026-10-30", "MARKET", 1, 1),
                new WorkspaceRepository.Entry("t", "c", "q", "{}", "{}", "QUEUED", 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        store.create(
                                new WorkspaceRepository.Conversation(
                                        "c", "b", "000001", "2026-10-30", "MARKET", 1, 1),
                                new WorkspaceRepository.Entry(
                                        "u", "c", "q", "{}", "{}", "QUEUED", 1, 1)));
        assertEquals(1, store.entries("c").size());
    }
}
