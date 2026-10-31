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
    public String chat(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        org.springframework.ai.chat.model.ChatResponse response = delegate.call(new Prompt(prompt));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("模型未返回回答正文");
        }
        return response.getResult().getOutput().getText();
    }
}
