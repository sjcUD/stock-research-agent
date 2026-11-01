package io.github.sjcud.stockagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** 将 Spring AI ChatModel 适配为项目内部 ChatModelGateway。 */
public class SpringAiChatModelGateway implements ChatModelGateway {
    private final ChatModel delegate;
    /** 可替换usage观测器，默认无操作，保持直接构造的兼容性。 */
    private final ModelUsageListener usageListener;

    public SpringAiChatModelGateway(ChatModel delegate) {
        this(delegate, (input, output) -> { });
    }

    /** 装配层注入运行监听器，不把供应商SDK暴露给预算模块。 */
    public SpringAiChatModelGateway(ChatModel delegate, ModelUsageListener usageListener) {
        if (delegate == null || usageListener == null) {
            throw new IllegalArgumentException("ChatModel 不能为空");
        }
        this.delegate = delegate;
        this.usageListener = usageListener;
    }

    /** 调用模型并返回回答正文。 */
    @Override
    public String chat(String prompt) { return complete(prompt).getText(); }

    /** 保留Spring AI供应商元数据，不读取或记录密钥和原始HTTP响应。 */
    @Override public ChatCompletion complete(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        usageListener.beforeCall();
        org.springframework.ai.chat.model.ChatResponse response = delegate.call(new Prompt(prompt));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("模型未返回回答正文");
        }
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        // EmptyUsage不表示免费调用，不能把缺失usage记成0。
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        if ((input != null && input < 0) || (output != null && output < 0)) throw new IllegalArgumentException("供应商usage为负数");
        // DefaultUsage会将缺失分项补0。对非空prompt/回答，只有正数分项且总量一致才可退款，零值保守视为未知。
        boolean known = usage != null && !(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
                && input != null && output != null && input > 0 && output > 0
                && total != null && (long) input + output == total.longValue();
        ChatCompletion completion = new ChatCompletion(response.getResult().getOutput().getText(),
                known ? input : null, known ? output : null);
        usageListener.onUsage(completion.getInputTokens(), completion.getOutputTokens());
        return completion;
    }
}
