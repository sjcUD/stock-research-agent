package io.github.sjcud.stockagent.research;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.Citation;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证聊天研究会先检索，再把证据交给分析师。 */
public class ResearchChatServiceTest {
    @Test
    public void shouldRetrieveEvidenceBeforeChat() {
        ChatModelGateway model = new ChatModelGateway() {
            @Override
            public String chat(String prompt) {
                return prompt;
            }
        };
        ResearchChatService service = new ResearchChatService(new FakeStore(), new FundamentalsAnalyst(model));

        ResearchChatAnswer answer = service.ask("收入增长如何", new MarketSnapshot("600519", "1680", "2026-10-30", "SAMPLE"));

        assertTrue(answer.getAnswer().contains("收入增长"));
        assertTrue(answer.getEvidence().get(0).getCitation() != null);
    }

    private static class FakeStore implements VectorStore {
        @Override public void upsert(List<DocumentChunk> chunks) { }
        @Override public List<RetrievalHit> search(RetrievalQuery query) {
            return Collections.singletonList(new RetrievalHit("c1", "收入增长证据", 0.9D,
                    new Citation("doc", "rev", 1, 0)));
        }
    }
}
