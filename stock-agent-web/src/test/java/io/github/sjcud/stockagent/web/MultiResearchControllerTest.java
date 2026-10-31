package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.research.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

/** 控制器边界验证：不依赖真实模型和数据库。 */
public class MultiResearchControllerTest {
    @Test
    public void shouldReturn503WhenDependenciesAreNotReady() {
        MultiResearchController controller = new MultiResearchController(
                new StaticListableBeanFactory().getBeanProvider(MultiResearchService.class));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.ask(request()));
        assertEquals(503, exception.getStatusCode().value());
    }

    @Test
    public void shouldReturnReportsAndRejectMissingQuestion() {
        VectorStore store = new VectorStore() {
            public void upsert(List<DocumentChunk> chunks) { }
            public List<RetrievalHit> search(RetrievalQuery query) { return Collections.emptyList(); }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("multiResearchService", new MultiResearchService(store,
                DefaultAnalystFactory.create(prompt -> "缺失数据，不能判断"),
                new PortfolioManager(prompt -> "需继续核验")));
        MultiResearchController controller = new MultiResearchController(beans.getBeanProvider(MultiResearchService.class));
        assertEquals(5, controller.ask(request()).getDecision().getAnalystResults().size());
        MultiResearchController.MultiRequest invalid = request();
        invalid.setQuestion(" ");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.ask(invalid)).getStatusCode().value());
    }

    private MultiResearchController.MultiRequest request() {
        MultiResearchController.MultiRequest request = new MultiResearchController.MultiRequest();
        request.setQuestion("研究一下"); request.setSymbol("600519"); request.setClose("1680");
        request.setAsOf("2026-10-30"); request.setSource("SAMPLE");
        return request;
    }
}
