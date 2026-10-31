package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sjcud.stockagent.runtime.research.InMemoryResearchRunRepository;
import io.github.sjcud.stockagent.runtime.research.ResearchRunService;
import org.junit.jupiter.api.Test;

/** 验证 Web 控制器的创建运行边界。 */
public class ResearchControllerTest {

    @Test
    public void shouldCreateRunResponse() {
        ResearchController controller = new ResearchController(
                new ResearchRunService(new InMemoryResearchRunRepository()));
        ResearchController.ResearchRunRequest request = new ResearchController.ResearchRunRequest();
        request.setConversationId(1L);
        request.setRequestJson("{\"symbol\":\"600519\"}");

        ResearchController.ResearchRunResponse response = controller.create(request);

        assertEquals(1L, response.getId().longValue());
        assertEquals("QUEUED", response.getStatus());
    }
}
