package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.llm.*;

import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.atomic.*;

/** 显式联网验证项目实际装配的流式模型与usage结算，不保存供应商原始响应。 */
public class P10StreamingLiveIntegrationTest {
    @Test
    public void receivesSeveralActualDeltasAndSettlesUsageOnce() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("P10_STREAM_INTEGRATION")));
        ChatModelProviderConfiguration config = new ChatModelProviderConfiguration();
        try (ModelHttpTransport transport = new ModelHttpTransport(5000, 60000)) {
            ChatModel model =
                    config.chatModel(
                            required("STOCK_AGENT_CHAT_API_KEY"),
                            value("STOCK_AGENT_CHAT_BASE_URL", "https://api.deepseek.com"),
                            value("STOCK_AGENT_CHAT_MODEL", "deepseek-chat"),
                            512,
                            value("STOCK_AGENT_CHAT_THINKING_MODE", ""),
                            transport);
            AtomicInteger chunks = new AtomicInteger(), settlements = new AtomicInteger();
            AtomicBoolean finished = new AtomicBoolean();
            SpringAiChatModelGateway gateway =
                    new SpringAiChatModelGateway(
                            model,
                            (input, output) -> {
                                assertNotNull(input);
                                assertNotNull(output);
                                settlements.incrementAndGet();
                            },
                            () -> 60000L);
            ChatCompletion answer =
                    gateway.completeStreaming(
                            "请用中文分三条解释Agent的预算、超时和取消，每条一句话。",
                            delta -> {
                                assertFalse(finished.get());
                                assertFalse(delta.isEmpty());
                                chunks.incrementAndGet();
                            });
            finished.set(true);
            assertTrue(chunks.get() > 1);
            assertFalse(answer.getText().isEmpty());
            assertEquals(1, settlements.get());
            System.out.println(
                    "P10真实流式验收：delta="
                            + chunks.get()
                            + "，input="
                            + answer.getInputTokens()
                            + "，output="
                            + answer.getOutputTokens()
                            + "，结算="
                            + settlements.get());
        }
    }

    private String required(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) throw new IllegalStateException(key + "未配置");
        return v;
    }

    private String value(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
