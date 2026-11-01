package io.github.sjcud.stockagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** 将 Spring AI ChatModel 适配为项目内部 ChatModelGateway。 */
public class SpringAiChatModelGateway implements ChatModelGateway {
    private final ChatModel delegate;

    /** 可替换usage观测器，默认无操作，保持直接构造的兼容性。 */
    private final ModelUsageListener usageListener;

    /** 由装配层提供阶段剩余时限，不反向依赖runtime。 */
    private final java.util.function.LongSupplier streamTimeoutMillis;

    public SpringAiChatModelGateway(ChatModel delegate) {
        this(delegate, (input, output) -> {});
    }

    /** 装配层注入运行监听器，不把供应商SDK暴露给预算模块。 */
    public SpringAiChatModelGateway(ChatModel delegate, ModelUsageListener usageListener) {
        this(delegate, usageListener, () -> 60000L);
    }

    /** 流式请求也必须受阶段总截止约束。 */
    public SpringAiChatModelGateway(
            ChatModel delegate,
            ModelUsageListener usageListener,
            java.util.function.LongSupplier timeout) {
        if (delegate == null || usageListener == null || timeout == null) {
            throw new IllegalArgumentException("ChatModel 不能为空");
        }
        this.delegate = delegate;
        this.usageListener = usageListener;
        this.streamTimeoutMillis = timeout;
    }

    /** 调用模型并返回回答正文。 */
    @Override
    public String chat(String prompt) {
        return complete(prompt).getText();
    }

    /** 保留Spring AI供应商元数据，不读取或记录密钥和原始HTTP响应。 */
    @Override
    public ChatCompletion complete(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        usageListener.beforeCall();
        org.springframework.ai.chat.model.ChatResponse response = delegate.call(new Prompt(prompt));
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("模型未返回回答正文");
        }
        org.springframework.ai.chat.metadata.Usage usage =
                response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return finish(response.getResult().getOutput().getText(), usage);
    }

    /** 真实Spring AI原生流式能力，不通过完整chat拆分模拟。 */
    @Override
    public boolean supportsStreaming() {
        return delegate instanceof org.springframework.ai.chat.model.StreamingChatModel;
    }

    /** 整个流只预留一次调用，最后可信usage只结算一次；中断/超时关闭订阅。 */
    @Override
    public ChatCompletion completeStreaming(
            String prompt, java.util.function.Consumer<String> onDelta) {
        if (!supportsStreaming()) return ChatModelGateway.super.completeStreaming(prompt, onDelta);
        if (prompt == null || prompt.trim().isEmpty() || onDelta == null)
            throw new IllegalArgumentException("流式输入无效");
        usageListener.beforeCall();
        long timeout = streamTimeoutMillis.getAsLong();
        if (timeout <= 0) throw new IllegalStateException("模型流达到截止时间");
        reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> source =
                ((org.springframework.ai.chat.model.StreamingChatModel) delegate)
                        .stream(new Prompt(prompt));
        reactor.core.publisher.Mono<Void> deadline =
                reactor.core.publisher.Mono.delay(java.time.Duration.ofMillis(timeout))
                        .then(
                                reactor.core.publisher.Mono.<Void>error(
                                        new IllegalStateException("模型流达到截止时间")));
        StringBuilder text = new StringBuilder();
        org.springframework.ai.chat.metadata.Usage usage = null;
        try (java.util.stream.Stream<org.springframework.ai.chat.model.ChatResponse> stream =
                source.takeUntilOther(deadline).toStream()) {
            java.util.Iterator<org.springframework.ai.chat.model.ChatResponse> iterator =
                    stream.iterator();
            while (iterator.hasNext()) {
                if (Thread.currentThread().isInterrupted())
                    throw new IllegalStateException("模型流已取消");
                org.springframework.ai.chat.model.ChatResponse chunk = iterator.next();
                if (chunk.getMetadata() != null
                        && chunk.getMetadata().getUsage() != null
                        && !(chunk.getMetadata().getUsage()
                                instanceof org.springframework.ai.chat.metadata.EmptyUsage))
                    usage = chunk.getMetadata().getUsage();
                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) continue;
                String delta = chunk.getResult().getOutput().getText();
                if (delta == null || delta.isEmpty()) continue;
                if ((long) text.length() + delta.length() > 200000)
                    throw new IllegalStateException("模型输出超过上限");
                text.append(delta);
                onDelta.accept(delta);
            }
        }
        if (text.length() == 0) throw new IllegalStateException("模型流未返回正文");
        return finish(text.toString(), usage);
    }

    /** 普通和增量响应共用可信usage验证，不能按每块累计重复的总usage。 */
    private ChatCompletion finish(String text, org.springframework.ai.chat.metadata.Usage usage) {
        // EmptyUsage不表示免费调用，不能把缺失usage记成0。
        Integer input = usage == null ? null : usage.getPromptTokens();
        Integer output = usage == null ? null : usage.getCompletionTokens();
        Integer total = usage == null ? null : usage.getTotalTokens();
        if ((input != null && input < 0) || (output != null && output < 0))
            throw new IllegalArgumentException("供应商usage为负数");
        // DefaultUsage会将缺失分项补0。对非空prompt/回答，只有正数分项且总量一致才可退款，零值保守视为未知。
        boolean known =
                usage != null
                        && !(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)
                        && input != null
                        && output != null
                        && input > 0
                        && output > 0
                        && total != null
                        && (long) input + output == total.longValue();
        ChatCompletion completion =
                new ChatCompletion(text, known ? input : null, known ? output : null);
        usageListener.onUsage(completion.getInputTokens(), completion.getOutputTokens());
        return completion;
    }
}
