package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import java.util.List;

/** 聊天研究回答及其检索证据。 */
public final class ResearchChatAnswer {
    private final String answer;
    private final List<RetrievalHit> evidence;

    public ResearchChatAnswer(String answer, List<RetrievalHit> evidence) {
        this.answer = answer;
        this.evidence = evidence;
    }

    public String getAnswer() { return answer; }
    public List<RetrievalHit> getEvidence() { return evidence; }
}
