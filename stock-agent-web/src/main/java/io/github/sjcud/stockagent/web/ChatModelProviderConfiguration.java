package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.llm.ChatModelGateway;
import io.github.sjcud.stockagent.llm.SpringAiChatModelGateway;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.util.Collections;

/** OpenAI 兼容 ChatModel 配置；配置 Chat API Key 后启用真实聊天模型。 */
@Configuration
public class ChatModelProviderConfiguration {
    /** 保留测试和嵌入式调用方使用的旧签名，采用默认模型超时。 */
    public ChatModel chatModel(
            String apiKey, String baseUrl, String model, Integer maxTokens, String thinkingMode) {
        return chatModel(
                apiKey,
                baseUrl,
                model,
                maxTokens,
                thinkingMode,
                new ModelHttpTransport(5000, 60000));
    }

    /** 创建真实 ChatModel。 */
    @Bean
    public ChatModel chatModel(
            @Value("${stock.agent.chat.api-key:}") String apiKey,
            @Value("${stock.agent.chat.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${stock.agent.chat.model:deepseek-chat}") String model,
            @Value("${stock.agent.chat.max-tokens:2048}") Integer maxTokens,
            @Value("${stock.agent.chat.thinking-mode:}") String thinkingMode,
            @Value("${stock.agent.chat.connect-timeout-ms:5000}") Integer connectTimeoutMillis,
            @Value("${stock.agent.chat.read-timeout-ms:60000}") Integer readTimeoutMillis) {
        return chatModel(
                apiKey,
                baseUrl,
                model,
                maxTokens,
                thinkingMode,
                new ModelHttpTransport(connectTimeoutMillis, readTimeoutMillis));
    }

    /** 使用指定 HTTP 工厂创建模型，测试和其他部署可注入独立传输策略。 */
    public ChatModel chatModel(
            String apiKey,
            String baseUrl,
            String model,
            Integer maxTokens,
            String thinkingMode,
            ClientHttpRequestFactory requestFactory) {
        // 显式固定模型传输，避免新增数据模块的 Apache 依赖改变 RestClient 的自动选择。
        // JDK 运行基线为21，源码仍使用 Java8 风格；不改变 OpenAI 兼容协议适配。
        OpenAiApi api =
                OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                        .build();
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(0.0D);
        options.setMaxTokens(maxTokens);
        // 原生流结束时请求usage块，保持一次Harness尝试只结算一次。
        options.setStreamUsage(true);
        // thinking 是 DeepSeek 等供应商扩展，不属于所有 OpenAI 兼容服务的公共契约。
        // 只有显式配置才发送，空值保持供应商默认，禁止根据 URL 猜测模型能力。
        if (thinkingMode != null && !thinkingMode.trim().isEmpty()) {
            if (!"enabled".equals(thinkingMode) && !"disabled".equals(thinkingMode)) {
                throw new IllegalArgumentException("thinking-mode 只能为 enabled、disabled 或空值");
            }
            options.setExtraBody(
                    Collections.<String, Object>singletonMap(
                            "thinking", Collections.singletonMap("type", thinkingMode)));
        }
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    /** 将模型适配为研究 Agent 使用的网关。 */
    @Bean
    public ChatModelGateway chatModelGateway(ChatModel chatModel) {
        return new SpringAiChatModelGateway(
                chatModel,
                new io.github.sjcud.stockagent.llm.ModelUsageListener() {
                    @Override
                    public void beforeCall() {
                        io.github.sjcud.stockagent.runtime.harness.ModelUsageScope
                                .beforeModelCall();
                    }

                    @Override
                    public void onUsage(Integer input, Integer output) {
                        io.github.sjcud.stockagent.runtime.harness.ModelUsageScope.record(
                                input, output);
                    }
                },
                () ->
                        io.github.sjcud.stockagent.runtime.harness.CallDeadline.boundedMillis(
                                60000L));
    }
}
