package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import java.util.List;
import io.github.sjcud.stockagent.runtime.harness.ContextPolicy;

/** 聊天研究回答及其检索证据。 */
public final class ResearchChatAnswer {
    private final String answer;
    private final List<RetrievalHit> evidence;
    private final ContextPolicy.Report contextReport;

    public ResearchChatAnswer(String answer, List<RetrievalHit> evidence) {
        this(answer, evidence, null);
    }

    /** 创建带上下文裁剪诊断的回答。 */
    public ResearchChatAnswer(String answer, List<RetrievalHit> evidence,
            ContextPolicy.Report contextReport) {
        this.answer = answer;
        this.evidence = evidence;
        this.contextReport = contextReport;
    }

    public String getAnswer() { return answer; }
    public List<RetrievalHit> getEvidence() { return evidence; }
    /** 返回上下文压缩前后估算，供学习 UI 展示。 */
    public ContextPolicy.Report getContextReport() { return contextReport; }
}
