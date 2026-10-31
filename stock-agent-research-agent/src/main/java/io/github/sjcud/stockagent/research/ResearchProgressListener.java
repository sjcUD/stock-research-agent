package io.github.sjcud.stockagent.research;

/** 研究进度观察接口，不包含提示词或模型正文，避免状态通道泄露上下文。 */
public interface ResearchProgressListener {
    /** 无观察者时保持原有同步接口行为。 */
    ResearchProgressListener NONE = (phase,role,status) -> { };
    /** 真实阶段开始或完成时通知观察者，允许并发角色同时调用。 */
    void onProgress(String phase, String role, String status);
}
