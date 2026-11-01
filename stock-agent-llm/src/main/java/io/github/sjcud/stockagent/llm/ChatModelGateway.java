package io.github.sjcud.stockagent.llm;

/**
 * Chat 模型能力边界。
 *
 * <p>研究 Agent 只能依赖这个接口，具体 provider 的 ChatModel 适配放在 stock-agent-llm 模块中，避免业务编排被某个厂商 SDK 锁定。
 */
public interface ChatModelGateway {

    /**
     * 根据已准备好的消息生成一次回答。
     *
     * @param prompt 已经过上下文和预算策略处理的提示
     * @return 模型回答文本
     */
    String chat(String prompt);

    /** 原生增量能力标识；默认兼容网关不宣称支持逐块输出。 */
    default boolean supportsStreaming() {
        return false;
    }

    /** 不支持流式的自定义网关只回传一次完整正文，不伪造Token节奏。 */
    default ChatCompletion completeStreaming(
            String prompt, java.util.function.Consumer<String> onDelta) {
        ChatCompletion result = complete(prompt);
        onDelta.accept(result.getText());
        return result;
    }

    /** 可选usage能力；保留lambda网关兼容，不支持时返回null计数。 */
    default ChatCompletion complete(String prompt) {
        return new ChatCompletion(chat(prompt), null, null);
    }
}
