package io.github.sjcud.stockagent;

/**
 * L0 最小工具闭环探针。
 *
 * <p>这个类用普通 Java 模拟模型提出工具意图、执行只读工具、回填工具结果
 * 和生成最终回答的过程。它不是生产 Agent，也不替代 Spring AI Alibaba 的
 * ReactAgent；它先把消息协议讲清楚，后续框架探针会替换内部循环。</p>
 */
public class AgentToolLoopProbe {

    /**
     * 根据股票代码运行一次最小研究。
     *
     * @param symbol 证券代码，当前探针只接受非空字符串
     * @return 使用工具结果生成的演示回答
     */
    public String run(String symbol) {
        String normalizedSymbol = normalizeSymbol(symbol);
        ToolCall toolCall = new ToolCall("getMarketSnapshot", normalizedSymbol);
        ToolResult toolResult = executeTool(toolCall);
        return renderAnswer(toolResult);
    }

    /**
     * 统一证券代码输入，避免工具层接收空白标识。
     */
    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) {
            throw new IllegalArgumentException("symbol 不能为空");
        }
        return symbol.trim();
    }

    /**
     * 执行只读市场工具。
     *
     * <p>当前使用固定样例数据，目的是让 L0 不依赖网络；真实数据 Provider
     * 将在后续阶段通过接口替换。</p>
     */
    private ToolResult executeTool(ToolCall toolCall) {
        if (!"getMarketSnapshot".equals(toolCall.getName())) {
            throw new IllegalArgumentException("未知工具: " + toolCall.getName());
        }
        return new ToolResult(toolCall.getSymbol(), "1680.00");
    }

    /**
     * 把工具结果渲染为用户可读回答，验证工具结果确实回到了最终消息。
     */
    private String renderAnswer(ToolResult toolResult) {
        return toolResult.getSymbol() + " 的演示收盘价为 " + toolResult.getClose();
    }

    /** 模型提出的结构化动作意图。 */
    private static class ToolCall {
        private final String name;
        private final String symbol;

        private ToolCall(String name, String symbol) {
            this.name = name;
            this.symbol = symbol;
        }

        private String getName() {
            return name;
        }

        private String getSymbol() {
            return symbol;
        }
    }

    /** 工具执行后的结构化观察结果。 */
    private static class ToolResult {
        private final String symbol;
        private final String close;

        private ToolResult(String symbol, String close) {
            this.symbol = symbol;
            this.close = close;
        }

        private String getSymbol() {
            return symbol;
        }

        private String getClose() {
            return close;
        }
    }
}
