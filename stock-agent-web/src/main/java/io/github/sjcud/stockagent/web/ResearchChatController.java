package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.research.ResearchChatAnswer;
import io.github.sjcud.stockagent.research.ResearchChatService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 聊天式研究 API，返回回答和可展开的检索证据。 */
@RestController
@RequestMapping("/api/research/chat")
public class ResearchChatController {
    private final ObjectProvider<ResearchChatService> serviceProvider;

    public ResearchChatController(ObjectProvider<ResearchChatService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    /** 接收问题和行情快照，返回带证据的研究回答。 */
    @PostMapping
    public ChatResponse ask(@RequestBody ChatRequest request) {
        ResearchChatService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "ResearchChatService 未就绪，请检查 ChatModel 和 Qdrant 配置");
        }
        ResearchChatAnswer answer = service.ask(request.getQuestion(),
                new MarketSnapshot(request.getSymbol(), request.getClose(), request.getAsOf(), request.getSource()));
        return new ChatResponse(answer.getAnswer(), answer.getEvidence());
    }

    /** 聊天请求体。 */
    public static class ChatRequest {
        private String question;
        private String symbol;
        private String close;
        private String asOf;
        private String source;
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        public String getClose() { return close; }
        public void setClose(String close) { this.close = close; }
        public String getAsOf() { return asOf; }
        public void setAsOf(String asOf) { this.asOf = asOf; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    /** 聊天响应体。 */
    public static class ChatResponse {
        private final String answer;
        private final List<RetrievalHit> evidence;
        public ChatResponse(String answer, List<RetrievalHit> evidence) {
            this.answer = answer;
            this.evidence = evidence;
        }
        public String getAnswer() { return answer; }
        public List<RetrievalHit> getEvidence() { return evidence; }
    }
}
