package io.github.sjcud.stockagent.runtime.research;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 验证运行记录服务不依赖具体数据库。 */
public class ResearchRunServiceTest {

    @Test
    public void shouldCreateQueuedRun() {
        ResearchRunRecord record = new ResearchRunService(new InMemoryResearchRunRepository())
                .start(7L, "{\"symbol\":\"600519\"}");

        assertEquals(1L, record.getId().longValue());
        assertEquals("QUEUED", record.getStatus());
    }
}
