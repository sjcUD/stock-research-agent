package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.llm.SpringAiChatModelGateway;
import java.util.Collections;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** OpenAI 兼容 ChatModel 配置；配置 Chat API Key 后启用真实聊天模型。 */
@Configuration
public class ChatModelProviderConfiguration {

    /** 创建真实 ChatModel。 */
    @Bean
    public ChatModel chatModel(
            @Value("${stock.agent.chat.api-key:}") String apiKey,
            @Value("${stock.agent.chat.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${stock.agent.chat.model:deepseek-chat}") String model,
            @Value("${stock.agent.chat.max-tokens:2048}") Integer maxTokens,
            @Value("${stock.agent.chat.thinking-mode:}") String thinkingMode) {
        // 显式固定模型传输，避免新增数据模块的 Apache 依赖改变 RestClient 的自动选择。
        // JDK 运行基线为21，源码仍使用 Java8 风格；不改变 OpenAI 兼容协议适配。
        OpenAiApi api = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())).build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(0.0D);
        options.setMaxTokens(maxTokens);
        // thinking 是 DeepSeek 等供应商扩展，不属于所有 OpenAI 兼容服务的公共契约。
        // 只有显式配置才发送，空值保持供应商默认，禁止根据 URL 猜测模型能力。
        if (thinkingMode != null && !thinkingMode.trim().isEmpty()) {
            if (!"enabled".equals(thinkingMode) && !"disabled".equals(thinkingMode)) {
                throw new IllegalArgumentException("thinking-mode 只能为 enabled、disabled 或空值");
            }
            options.setExtraBody(Collections.<String, Object>singletonMap("thinking",
                    Collections.singletonMap("type", thinkingMode)));
        }
        return OpenAiChatModel.builder().openAiApi(api).defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }

    /** 将模型适配为研究 Agent 使用的网关。 */
    @Bean
    public ChatModelGateway chatModelGateway(ChatModel chatModel) {
        return new SpringAiChatModelGateway(chatModel);
    }
}
