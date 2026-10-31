package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.llm.SpringAiChatModelGateway;
import io.github.sjcud.stockagent.llm.SpringAiEmbeddingGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

/** OpenAI 兼容 ChatModel 配置；配置 Chat API Key 后启用真实聊天模型。 */
@Configuration
@ConditionalOnProperty(name = "stock.agent.chat.api-key")
public class ChatModelProviderConfiguration {

    /** 创建真实 ChatModel。 */
    @Bean
    public ChatModel chatModel(
            @Value("${stock.agent.chat.api-key}") String apiKey,
            @Value("${stock.agent.chat.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${stock.agent.chat.model:deepseek-chat}") String model,
            @Value("${stock.agent.chat.max-tokens:2048}") Integer maxTokens) {
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(0.0D);
        options.setMaxTokens(maxTokens);
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    /** 将模型适配为研究 Agent 使用的网关。 */
    @Bean
    public ChatModelGateway chatModelGateway(ChatModel chatModel) {
        return new SpringAiChatModelGateway(chatModel);
    }
}
