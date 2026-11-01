package io.github.sjcud.stockagent.evaluation;

import java.util.*;

/** 单次实验的延迟分布与Wilson成功率区间；重复同一问题不是独立泛化样本。 */
public final class EvaluationStatistics {
    /** 毫秒样本和成功数量只属于当前实验，调用方不要跨模型复用。 */
    private final List<Double> latency = new ArrayList<Double>();
    private int successes;
    /** 错误尝试也保留耗时和分母。 */
    public void record(double millis, boolean success) {
        if (!Double.isFinite(millis) || millis < 0) throw new IllegalArgumentException("延迟无效");
        latency.add(millis); if (success) successes++;
    }
    /** 最近秩分位数与95% Wilson区间；空样本返回null而不是100%。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("samples", latency.size()); row.put("successes", successes);
        if (latency.isEmpty()) { row.put("latencyP50Millis", null); row.put("latencyP95Millis", null);
            row.put("successLower95", null); row.put("successUpper95", null); return row; }
        List<Double> sorted = new ArrayList<Double>(latency); Collections.sort(sorted);
        row.put("latencyP50Millis", sorted.get((int) Math.ceil(sorted.size() * .5) - 1));
        row.put("latencyP95Millis", sorted.get((int) Math.ceil(sorted.size() * .95) - 1));
        double n = sorted.size(), p = successes / n, z = 1.959963984540054, denom = 1 + z * z / n;
        double center = (p + z * z / (2 * n)) / denom;
        double radius = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
        row.put("successLower95", Math.max(0, center - radius)); row.put("successUpper95", Math.min(1, center + radius));
        return row;
    }
}
