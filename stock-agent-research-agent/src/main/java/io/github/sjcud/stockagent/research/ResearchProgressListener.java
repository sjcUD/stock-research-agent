package io.github.sjcud.stockagent.research;

/** 阶段事件与受控模型轨迹使用不同回调；调用者自行决定归档策略。 */
public interface ResearchProgressListener {
    /** 无观察者时保持原有同步接口行为。 */
    ResearchProgressListener NONE = (phase, role, status) -> {};

    /** 真实阶段开始或完成时通知观察者，允许并发角色同时调用。 */
    void onProgress(String phase, String role, String status);

    /** 完成一次真实角色调用后归档输入/输出；默认不处理以兼容现有同步接口。 */
    default void onTrace(AgentTrace trace) {}
}
