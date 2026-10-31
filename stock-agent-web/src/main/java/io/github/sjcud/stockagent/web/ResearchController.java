package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.runtime.research.ResearchRunRecord;
import io.github.sjcud.stockagent.runtime.research.ResearchRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 研究运行 API，先提供创建任务的最小 HTTP 边界。 */
@RestController
@RequestMapping("/api/research/runs")
public class ResearchController {

    private final ResearchRunService service;

    public ResearchController(ResearchRunService service) {
        this.service = service;
    }

    /** 创建一条排队状态的研究运行记录。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResearchRunResponse create(@RequestBody ResearchRunRequest request) {
        ResearchRunRecord record = service.start(request.getConversationId(), request.getRequestJson());
        return new ResearchRunResponse(record.getId(), record.getStatus());
    }

    /** API 请求体，保持 Java 8 普通 Bean 形式。 */
    public static class ResearchRunRequest {
        private long conversationId;
        private String requestJson;

        public long getConversationId() { return conversationId; }
        public void setConversationId(long conversationId) { this.conversationId = conversationId; }
        public String getRequestJson() { return requestJson; }
        public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    }

    /** API 返回体。 */
    public static class ResearchRunResponse {
        private final Long id;
        private final String status;

        public ResearchRunResponse(Long id, String status) {
            this.id = id;
            this.status = status;
        }

        public Long getId() { return id; }
        public String getStatus() { return status; }
    }
}
