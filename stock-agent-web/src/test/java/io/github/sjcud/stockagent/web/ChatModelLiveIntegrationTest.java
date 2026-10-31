package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/** 真实 ChatModel 联调测试，仅通过显式环境变量启用。 */
public class ChatModelLiveIntegrationTest {

    @Test
    public void shouldReturnChatAnswer() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CHAT_INTEGRATION")));
        String apiKey = required("STOCK_AGENT_CHAT_API_KEY");
        String baseUrl = valueOrDefault("STOCK_AGENT_CHAT_BASE_URL", "https://api.deepseek.com");
        String modelName = valueOrDefault("STOCK_AGENT_CHAT_MODEL", "deepseek-chat");
        int maxTokens = Integer.parseInt(valueOrDefault("STOCK_AGENT_CHAT_MAX_TOKENS", "2048"));

        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(modelName);
        options.setTemperature(0.0D);
        options.setMaxTokens(maxTokens);
        ChatModel model = OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).build();

        String answer = model.call(new Prompt("只回答：联调成功")).getResult().getOutput().getText();

        assertFalse(answer == null || answer.trim().isEmpty());
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " 未配置");
        }
        return value;
    }

    private String valueOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
