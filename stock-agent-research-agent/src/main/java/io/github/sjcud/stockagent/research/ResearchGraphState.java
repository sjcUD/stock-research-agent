package io.github.sjcud.stockagent.research;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import java.util.*;

/** Graph状态编码适配器：只用Map/List/String/Number，避免把不可反序列化的领域DTO塞进框架状态。 */
final class ResearchGraphState {
    private ResearchGraphState() { }
    /** 初始化当前问题、结构化行情和压缩证据，不包含任何模型或预算对象。 */
    static Map<String, Object> initial(String question, MarketSnapshot snapshot, String evidence) {
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("question", question); state.put("evidence", evidence);
        Map<String, Object> market = new LinkedHashMap<String, Object>();
        market.put("symbol", snapshot.getSymbol()); market.put("close", snapshot.getClose()); market.put("asOf", snapshot.getAsOf()); market.put("source", snapshot.getSource());
        state.put("market", market); return state;
    }
    /** 返回经过类型校验的文本，缺失状态禁止猜测默认事实。 */
    static String text(Map<?, ?> map, String key) {
        Object value = map.get(key); if (!(value instanceof String)) throw new IllegalStateException("图状态缺少文本：" + key); return (String) value;
    }
    /** 重建行情领域对象，节点实际读取图状态而非隐藏全局变量。 */
    static MarketSnapshot market(OverAllState state) {
        Map<?, ?> map = map(state.data().get("market"));
        return new MarketSnapshot(text(map, "symbol"), text(map, "close"), text(map, "asOf"), text(map, "source"));
    }
    /** 验证通用Map类型，不依赖Jackson反序列化领域对象。 */
    static Map<?, ?> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalStateException("图状态结构无效"); return (Map<?, ?>) value;
    }
    /** 角色结果拥有独立键，平行写入不竞争同一个reports列表。 */
    static String reportKey(AnalystRole role) { return "report_" + role.name(); }
    /** 将完整领域报告编码为基础类型。 */
    static Map<String, Object> encode(AnalystResult result) {
        Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("role", result.getRole().name());
        value.put("conclusion", result.getConclusion()); value.put("evidence", result.getEvidence());
        if (result.getTrace() != null) value.put("trace", encodeTrace(result.getTrace())); return value;
    }
    /** 轨迹正文可归档，不包含HTTP连接或运行期回调。 */
    static Map<String, Object> encodeTrace(AgentTrace trace) {
        Map<String, Object> value = new LinkedHashMap<String, Object>(); value.put("role", trace.getRole()); value.put("input", trace.getInput());
        value.put("output", trace.getOutput()); value.put("elapsedMillis", trace.getElapsedMillis()); return value;
    }
    /** 从状态恢复不可变轨迹，无轨迹的自定义角色保持null。 */
    static AgentTrace trace(Object raw) {
        if (raw == null) return null; Map<?, ?> map = map(raw); Object elapsed = map.get("elapsedMillis");
        if (!(elapsed instanceof Number)) throw new IllegalStateException("图轨迹耗时无效");
        return new AgentTrace(text(map, "role"), text(map, "input"), text(map, "output"), ((Number) elapsed).longValue());
    }
    /** 汇聚前校验每个注册角色都已写入，排序后只允许经理接收完整报告。 */
    static List<AnalystResult> reports(OverAllState state, List<ResearchAnalyst> analysts) {
        List<AnalystResult> reports = new ArrayList<AnalystResult>();
        for (ResearchAnalyst analyst : analysts) {
            Map<?, ?> value = map(state.data().get(reportKey(analyst.getRole())));
            if (!analyst.getRole().name().equals(text(value, "role"))) throw new IllegalStateException("图报告角色错配");
            reports.add(new AnalystResult(analyst.getRole(), text(value, "conclusion"), text(value, "evidence"), trace(value.get("trace"))));
        }
        Collections.sort(reports, Comparator.comparing(r -> r.getRole().name())); return reports;
    }
}
