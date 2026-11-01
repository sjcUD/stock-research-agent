package io.github.sjcud.stockagent.llm;

/**
 * Chat 模型能力边界。
 *
 * <p>研究 Agent 只能依赖这个接口，具体 provider 的 ChatModel 适配放在
 * stock-agent-llm 模块中，避免业务编排被某个厂商 SDK 锁定。</p>
 */
public interface ChatModelGateway {

    /**
     * 根据已准备好的消息生成一次回答。
     *
     * @param prompt 已经过上下文和预算策略处理的提示
     * @return 模型回答文本
     */
    String chat(String prompt);

    /** 可选usage能力；保留lambda网关兼容，不支持时返回null计数。 */
    default ChatCompletion complete(String prompt) {
        return new ChatCompletion(chat(prompt), null, null);
    }
}
