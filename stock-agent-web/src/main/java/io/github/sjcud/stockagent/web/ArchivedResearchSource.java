package io.github.sjcud.stockagent.web;

import com.fasterxml.jackson.databind.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.research.*;
import io.github.sjcud.stockagent.runtime.workspace.WorkspaceRepository;
import java.util.*;

/** 只从服务端成功归档恢复固定研究上下文；客户端不能上传旧报告或改写证据。 */
final class ArchivedResearchSource {
    /** 固定原问题/行情/证据与完整旧报告，均从归档JSON读取。 */
    final String question, symbol, asOf, conversationId, evidence;
    final MarketSnapshot snapshot;
    final List<AnalystResult> reports;
    final JsonNode marketData;
    /** 校验源任务为已完成的市场研究，不复活失败或取消任务。 */
    ArchivedResearchSource(WorkspaceRepository.Entry entry) {
        if (!"COMPLETED".equals(entry.status)) throw new IllegalArgumentException("只能重跑已完成的市场研究");
        try {
            ObjectMapper mapper = new ObjectMapper(); JsonNode request = mapper.readTree(entry.requestJson);
            if (!"MARKET".equals(request.path("scope").asText("MARKET"))) throw new IllegalArgumentException("资料问答不支持局部角色重跑");
            JsonNode result = mapper.readTree(entry.snapshotJson).path("result");
            marketData = result.path("marketData").deepCopy(); JsonNode market = marketData.path("snapshot");
            question = text(request, "question"); symbol = text(request, "symbol"); asOf = text(request, "asOf"); conversationId = entry.conversationId;
            snapshot = new MarketSnapshot(text(market, "symbol"), text(market, "close"), text(market, "asOf"), text(market, "source"));
            JsonNode oldReports = result.path("research").path("decision").path("analystResults");
            if (!oldReports.isArray() || oldReports.isEmpty()) throw new IllegalArgumentException("源任务缺少完整角色报告");
            List<AnalystResult> values = new ArrayList<AnalystResult>(); Set<AnalystRole> seen = EnumSet.noneOf(AnalystRole.class);
            for (JsonNode report : oldReports) {
                AnalystRole role = AnalystRole.valueOf(text(report, "role"));
                if (!seen.add(role)) throw new IllegalArgumentException("源任务报告角色重复");
                JsonNode t = report.path("trace"); AgentTrace trace = null;
                if (!t.isMissingNode() && !t.isNull()) {
                    if (!t.path("elapsedMillis").isIntegralNumber() || t.get("elapsedMillis").asLong() < 0 || !role.name().equals(text(t, "role"))) throw new IllegalArgumentException("源任务轨迹无效");
                    trace = new AgentTrace(role.name(), text(t, "input"), text(t, "output"), t.get("elapsedMillis").asLong());
                }
                values.add(new AnalystResult(role, text(report, "conclusion"), text(report, "evidence"), trace));
            }
            if (!seen.equals(EnumSet.allOf(AnalystRole.class))) throw new IllegalArgumentException("源任务角色报告不完整");
            evidence = values.get(0).getEvidence();
            for (AnalystResult report : values) if (!evidence.equals(report.getEvidence())) throw new IllegalArgumentException("源报告不属于同一证据快照");
            reports = Collections.unmodifiableList(values);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("源任务归档不能用于局部重跑"); }
    }
    /** 源数据缺失直接拒绝，不能用猜测的字符串补齐金融事实。 */
    private static String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().trim().isEmpty()) throw new IllegalArgumentException("源任务字段缺失：" + key);
        return value.asText();
    }
}
