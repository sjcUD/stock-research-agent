package io.github.sjcud.stockagent.evaluation;

import io.github.sjcud.stockagent.llm.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 评测专用观测装饰器，统计每次真实尝试，失败也保留分母，不输出提示词和异常正文。 */
public final class MeasuredChatGateway implements ChatModelGateway {
    /** 供应商无关模型和本实验独立的观测列表。 */
    private final ChatModelGateway delegate;
    private final List<Map<String, Object>> attempts = new ArrayList<Map<String, Object>>();
    /** 用户配置的每百万Token输入/输出单价；null时费用不计算。 */
    private final BigDecimal inputRate, outputRate;
    private final String currency;
    /** 单价必须同币种，缺失时不猜测当前模型价格。 */
    public MeasuredChatGateway(ChatModelGateway model, BigDecimal inputRate, BigDecimal outputRate, String currency) {
        if (model == null || (inputRate != null && inputRate.signum() < 0) || (outputRate != null && outputRate.signum() < 0)
                || ((inputRate != null || outputRate != null) && (currency == null || currency.trim().isEmpty())))
            throw new IllegalArgumentException("模型或费率配置无效");
        delegate = model; this.inputRate = inputRate; this.outputRate = outputRate; this.currency = currency;
    }
    /** 旧文本接口共用同一次调用，不重复收费。 */
    @Override public String chat(String prompt) { return complete(prompt).getText(); }
    /** 单次观测线程安全保存，响应正文不写入指标。 */
    @Override public ChatCompletion complete(String prompt) {
        long started = System.nanoTime(); Map<String, Object> row = new LinkedHashMap<String, Object>();
        try {
            ChatCompletion result = delegate.complete(prompt); row.put("success", true);
            row.put("inputTokens", result.getInputTokens()); row.put("outputTokens", result.getOutputTokens());
            BigDecimal cost = result.getInputTokens() == null || result.getOutputTokens() == null || inputRate == null || outputRate == null ? null
                : inputRate.multiply(BigDecimal.valueOf(result.getInputTokens())).add(outputRate.multiply(BigDecimal.valueOf(result.getOutputTokens()))).divide(BigDecimal.valueOf(1000000));
            row.put("estimatedCost", cost); row.put("currency", currency); return result;
        } catch (RuntimeException ex) { row.put("success", false); row.put("errorType", ex.getClass().getSimpleName()); throw ex; }
        finally { row.put("latencyMillis", TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 1000.0);
            synchronized (attempts) { attempts.add(row); } }
    }
    /** 完整尝试列表，不将失败或缺失usage当作0费用。 */
    public Map<String, Object> report() {
        EvaluationStatistics stats = new EvaluationStatistics(); List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        synchronized (attempts) { for (Map<String, Object> row : attempts) { rows.add(new LinkedHashMap<String, Object>(row)); stats.record((Double) row.get("latencyMillis"), Boolean.TRUE.equals(row.get("success"))); } }
        Map<String, Object> report = stats.snapshot(); report.put("attempts", rows);
        report.put("costBasis", "configured rates per million tokens; not provider invoice; unknown usage/cached billing not inferred"); return report;
    }
}
