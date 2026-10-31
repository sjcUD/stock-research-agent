package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 未复权人民币日线；暂不传递未统一单位的供应商成交量字段。 */
public final class DailyBar {
    /** 实际交易日期。 */
    private final String date;
    /** 开盘价，人民币元。 */
    private final BigDecimal open;
    /** 收盘价，人民币元。 */
    private final BigDecimal close;
    /** 最高价，人民币元。 */
    private final BigDecimal high;
    /** 最低价，人民币元。 */
    private final BigDecimal low;
    /** 校验价格为正且满足日内高低关系，拒绝把坏数据交给模型。 */
    public DailyBar(String date, String open, String close, String high, String low) {
        LocalDate.parse(date);
        this.date = date;
        this.open = price(open); this.close = price(close);
        this.high = price(high); this.low = price(low);
        if (this.low.signum() <= 0 || this.high.compareTo(this.low) < 0
                || this.open.compareTo(this.low) < 0 || this.open.compareTo(this.high) > 0
                || this.close.compareTo(this.low) < 0 || this.close.compareTo(this.high) > 0) {
            throw new IllegalArgumentException("日线价格不满足有效范围");
        }
    }
    /** 限制金额文本精度和长度，拒绝巨大指数导致 toPlainString 分配异常规模的文本。 */
    private static BigDecimal price(String value) {
        if (value == null || !value.matches("[0-9]{1,12}(\\.[0-9]{1,6})?")) {
            throw new IllegalArgumentException("价格须为最多12位整数、6位小数的普通十进制数字");
        }
        return new BigDecimal(value);
    }
    /** 返回交易日。 */
    public String getDate() { return date; }
    /** 返回开盘价。 */
    public BigDecimal getOpen() { return open; }
    /** 返回收盘价。 */
    public BigDecimal getClose() { return close; }
    /** 返回最高价。 */
    public BigDecimal getHigh() { return high; }
    /** 返回最低价。 */
    public BigDecimal getLow() { return low; }
}
