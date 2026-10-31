package io.github.sjcud.stockagent.runtime.research;

import java.util.concurrent.atomic.AtomicLong;

/** 本地开发和 Web 冒烟测试使用的内存仓储。 */
public class InMemoryResearchRunRepository implements ResearchRunRepository {

    private final AtomicLong sequence = new AtomicLong(0L);

    /** 返回自增 ID，不依赖外部数据库。 */
    @Override
    public ResearchRunRecord create(long conversationId, String status, String requestJson) {
        return new ResearchRunRecord(sequence.incrementAndGet(), conversationId, status, requestJson);
    }
}
