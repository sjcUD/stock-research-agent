package io.github.sjcud.stockagent.research;

/** 单个角色的执行快照；仅随响应回传，尚未持久化到数据库。 */
public final class AgentTrace {
    /** 稳定角色名，组合经理使用 PORTFOLIO_MANAGER。 */
    private final String role;
    /** 实际发送给模型的完整提示词，包含问题、快照与证据。 */
    private final String input;
    /** 模型返回的角色报告。 */
    private final String output;
    /** 使用单调时钟测量的耗时，单位毫秒。 */
    private final long elapsedMillis;

    /** 创建不可变的执行快照。 */
    public AgentTrace(String role, String input, String output, long elapsedMillis) {
        this.role = role;
        this.input = input;
        this.output = output;
        this.elapsedMillis = elapsedMillis;
    }
    /** 返回角色名。 */
    public String getRole() { return role; }
    /** 返回角色输入。 */
    public String getInput() { return input; }
    /** 返回角色输出。 */
    public String getOutput() { return output; }
    /** 返回角色耗时。 */
    public long getElapsedMillis() { return elapsedMillis; }
}
