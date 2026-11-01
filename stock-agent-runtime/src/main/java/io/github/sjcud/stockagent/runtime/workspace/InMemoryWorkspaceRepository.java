package io.github.sjcud.stockagent.runtime.workspace;

import java.util.*;

/** 未启用MySQL时的显式进程内实现，跨重启不保留，不在数据库失败后偷偷启用。 */
public final class InMemoryWorkspaceRepository implements WorkspaceRepository {
    /** 会话、任务和助手文本统一在仓储锁下更新。 */
    private final Map<String, Conversation> conversations = new HashMap<String, Conversation>();

    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final Map<String, String> answers = new HashMap<String, String>();

    /** 模拟数据库事务边界，并对无持久化模式设置总记录容量上限。 */
    @Override
    public synchronized void create(Conversation c, Entry t) {
        Conversation previous = conversations.get(c.id);
        if (previous != null
                && (!previous.symbol.equals(c.symbol) || !previous.asOf.equals(c.asOf)))
            throw new IllegalArgumentException("会话上下文不一致");
        if (entries.containsKey(t.id)) throw new IllegalArgumentException("任务已存在");
        if (entries.size() >= 500 || entries(c.id).size() >= 100)
            throw new IllegalArgumentException("历史容量已满，请新建会话或启用MySQL");
        conversations.put(
                c.id,
                new Conversation(
                        c.id,
                        previous == null ? c.title : previous.title,
                        c.symbol,
                        c.asOf,
                        c.scope,
                        previous == null ? c.createdAt : previous.createdAt,
                        t.updatedAt));
        entries.put(t.id, t);
        answers.put(t.id, "");
    }

    /** 终态条件更新与助手内容同时完成。 */
    @Override
    public synchronized boolean save(Entry next, String answer) {
        Entry old = get(next.id);
        if (!WorkspaceRepository.active(old.status)
                || !old.conversationId.equals(next.conversationId)) return false;
        entries.put(next.id, next);
        answers.put(next.id, answer);
        Conversation c = conversations.get(next.conversationId);
        conversations.put(
                c.id,
                new Conversation(
                        c.id, c.title, c.symbol, c.asOf, c.scope, c.createdAt, next.updatedAt));
        return true;
    }

    /** 不存在即404，不返回占位任务。 */
    @Override
    public synchronized Entry get(String id) {
        Entry value = entries.get(id);
        if (value == null) throw new NoSuchElementException("任务不存在");
        return value;
    }

    /** 稳定排序并限制页大小。 */
    @Override
    public synchronized List<Conversation> conversations(int limit, int offset) {
        WorkspaceRepository.validatePage(limit, offset);
        List<Conversation> result = new ArrayList<Conversation>(conversations.values());
        result.sort(
                Comparator.comparingLong((Conversation c) -> c.updatedAt)
                        .reversed()
                        .thenComparing(c -> c.id));
        return new ArrayList<Conversation>(
                result.subList(
                        Math.min(offset, result.size()), Math.min(offset + limit, result.size())));
    }

    /** 返回防御性列表，不让页面修改内部状态。 */
    @Override
    public synchronized List<Entry> entries(String cid) {
        List<Entry> result = new ArrayList<Entry>();
        for (Entry e : entries.values()) if (e.conversationId.equals(cid)) result.add(e);
        result.sort(Comparator.comparingLong((Entry e) -> e.createdAt).thenComparing(e -> e.id));
        return result;
    }

    /** 每轮一对消息，模型尚未回答时助手内容为空且任务状态明确。 */
    @Override
    public synchronized List<Message> messages(String cid) {
        List<Message> result = new ArrayList<Message>();
        long id = 1;
        for (Entry e : entries(cid)) {
            result.add(new Message(id++, cid, e.id, "USER", e.question, e.status, e.createdAt));
            result.add(
                    new Message(
                            id++,
                            cid,
                            e.id,
                            "ASSISTANT",
                            answers.get(e.id),
                            e.status,
                            e.createdAt));
        }
        return result;
    }

    /** 只改变活动任务，保留已完成、取消和失败历史。 */
    @Override
    public synchronized void interruptUnfinished() {
        for (Entry e : new ArrayList<Entry>(entries.values()))
            if (WorkspaceRepository.active(e.status))
                save(
                        new Entry(
                                e.id,
                                e.conversationId,
                                e.question,
                                e.requestJson,
                                e.snapshotJson,
                                "INTERRUPTED",
                                e.createdAt,
                                System.currentTimeMillis()),
                        "服务已重启，本次研究中断；请确认后重新发起。");
    }

    /** 显式告知客户端只是进程内历史。 */
    @Override
    public boolean durable() {
        return false;
    }
}
