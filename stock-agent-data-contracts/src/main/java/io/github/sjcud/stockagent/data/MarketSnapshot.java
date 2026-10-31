package io.github.sjcud.stockagent.data;

/**
 * 行情工具返回的最小结构化快照。
 *
 * <p>close 以字符串保存，避免在学习阶段把展示精度和金融金额计算
 * 偷换成 double；后续计算模块再转换为 BigDecimal。</p>
 */
public final class MarketSnapshot {
    /** 规范化证券代码。 */
    private final String symbol;
    /** 收盘价文本，按人民币元展示。 */
    private final String close;
    /** 实际行情日期，并非请求日期。 */
    private final String asOf;
    /** 数据来源标识，SAMPLE 必须明确标出。 */
    private final String source;

    /** 创建最小快照；完整数据质量校验在提供器边界完成。 */
    public MarketSnapshot(String symbol, String close, String asOf, String source) {
        this.symbol = symbol;
        this.close = close;
        this.asOf = asOf;
        this.source = source;
    }

    /** 返回证券代码。 */
    public String getSymbol() { return symbol; }
    /** 返回收盘价。 */
    public String getClose() { return close; }
    /** 返回实际行情日期。 */
    public String getAsOf() { return asOf; }
    /** 返回来源标识。 */
    public String getSource() { return source; }
}
