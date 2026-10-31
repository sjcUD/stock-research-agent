package io.github.sjcud.stockagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 验证最小工具闭环：模型提出工具动作，执行器回填工具结果，再生成最终回答。
 */
public class AgentToolLoopProbeTest {

    @Test
    public void shouldHaveThePinnedSpringAiAlibabaReactAgentClass() throws Exception {
        Class<?> reactAgent = Class.forName("com.alibaba.cloud.ai.graph.agent.ReactAgent");

        assertEquals("ReactAgent", reactAgent.getSimpleName());
    }

    @Test
    public void shouldReturnToolResultInFinalAnswer() {
        AgentToolLoopProbe probe = new AgentToolLoopProbe();
        String answer = probe.run("600519");

        assertEquals("600519 的演示收盘价为 1680.00", answer);
    }
}
