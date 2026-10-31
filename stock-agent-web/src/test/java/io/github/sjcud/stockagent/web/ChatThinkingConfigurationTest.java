package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 验证供应商扩展参数为显式可选项，避免传给不支持它的兼容服务。 */
public class ChatThinkingConfigurationTest {
    @Test
    public void shouldSendThinkingOnlyWhenExplicitlyConfigured() {
        ChatModelProviderConfiguration configuration = new ChatModelProviderConfiguration();
        OpenAiChatOptions disabled = (OpenAiChatOptions) configuration.chatModel(
                "test-key", "https://api.deepseek.com", "deepseek-chat", 2048, "disabled").getDefaultOptions();
        assertEquals("disabled", ((Map<?, ?>) disabled.getExtraBody().get("thinking")).get("type"));
        OpenAiChatOptions defaults = (OpenAiChatOptions) configuration.chatModel(
                "test-key", "https://api.deepseek.com", "deepseek-chat", 2048, "").getDefaultOptions();
        assertTrue(defaults.getExtraBody() == null || defaults.getExtraBody().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> configuration.chatModel(
                "test-key", "https://api.deepseek.com", "deepseek-chat", 2048, "wrong"));
    }
}
