package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** 代码计算技术指标；使用未复权收盘价，不让模型从单日价格猜指标。 */
public final class TechnicalIndicators {
    /** 工具类不需要实例化。 */
    private TechnicalIndicators() { }
    /** 按升序日线计算末日指标；缺少所需样本时省略，窗口初值与其他软件可能不同。 */
    public static Map<String, BigDecimal> calculate(List<DailyBar> bars) {
        Map<String, BigDecimal> result = new LinkedHashMap<String, BigDecimal>();
        int size = bars.size();
        for (int period : new int[] {5, 20}) {
            if (size >= period) {
                BigDecimal total = BigDecimal.ZERO;
                for (int i = size - period; i < size; i++) { total = total.add(bars.get(i).getClose()); }
                result.put("SMA" + period, total.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP));
            }
        }
        if (size >= 15) {
            double gain = 0, loss = 0;
            for (int i = 1; i <= 14; i++) {
                double change = bars.get(i).getClose().subtract(bars.get(i - 1).getClose()).doubleValue();
                gain += Math.max(change, 0) / 14; loss += Math.max(-change, 0) / 14;
            }
            // Wilder 平滑：首 14 个价格变化取均值，之后按 13/14 衰减。
            for (int i = 15; i < size; i++) {
                double change = bars.get(i).getClose().subtract(bars.get(i - 1).getClose()).doubleValue();
                gain = (gain * 13 + Math.max(change, 0)) / 14;
                loss = (loss * 13 + Math.max(-change, 0)) / 14;
            }
            result.put("RSI14", rounded(loss == 0 ? (gain == 0 ? 50 : 100) : 100 - 100 / (1 + gain / loss)));
        }
        if (size >= 35) {
            double fast = bars.get(0).getClose().doubleValue(), slow = fast, signal = 0, diff = 0;
            // EMA 用窗口首日收盘价初始化；柱值采用常见 A 股口径 2*(DIF-DEA)。
            for (int i = 1; i < size; i++) {
                double close = bars.get(i).getClose().doubleValue();
                fast += (close - fast) * 2 / 13; slow += (close - slow) * 2 / 27;
                diff = fast - slow; signal += (diff - signal) * 2 / 10;
            }
            result.put("MACD_DIF", rounded(diff)); result.put("MACD_DEA", rounded(signal));
            result.put("MACD_HIST", rounded(2 * (diff - signal)));
        }
        return Collections.unmodifiableMap(result);
    }
    /** 统一展示精度，指标为教学计算，不承担货币记账。 */
    private static BigDecimal rounded(double value) { return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP); }
}
