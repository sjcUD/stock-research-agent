package io.github.sjcud.stockagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** 将 Spring AI ChatModel 适配为项目内部 ChatModelGateway。 */
public class SpringAiChatModelGateway implements ChatModelGateway {
    private final ChatModel delegate;

    public SpringAiChatModelGateway(ChatModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("ChatModel 不能为空");
        }
        this.delegate = delegate;
    }

    /** 调用模型并返回回答正文。 */
    @Override
    public String chat(String prompt) { return complete(prompt).getText(); }

    /** 保留Spring AI供应商元数据，不读取或记录密钥和原始HTTP响应。 */
    @Override public ChatCompletion complete(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        org.springframework.ai.chat.model.ChatResponse response = delegate.call(new Prompt(prompt));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("模型未返回回答正文");
        }
        org.springframework.ai.chat.metadata.Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        // EmptyUsage不表示免费调用，不能把缺失usage记成0。
        boolean known = usage != null && !(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage);
        return new ChatCompletion(response.getResult().getOutput().getText(),
                known ? usage.getPromptTokens() : null, known ? usage.getCompletionTokens() : null);
    }
}
