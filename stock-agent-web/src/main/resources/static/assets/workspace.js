/** 可独立测试的展示逻辑，不访问 DOM、存储或网络。 */
export const roles = {
  FUNDAMENTALS: "基本面",
  TECHNICAL: "技术面",
  NEWS: "新闻",
  SENTIMENT: "情绪",
  RISK: "风险",
  PORTFOLIO_MANAGER: "组合经理",
};
export const terminal = (status) =>
  [
    "COMPLETED",
    "FAILED",
    "CANCELLED",
    "TIMED_OUT",
    "INTERRUPTED",
    "REJECTED",
  ].includes(status);
/** 兼容市场研究与资料问答两种响应，页面不猜测缺失结论。 */
export function normalizeResult(result = {}) {
  const research = result.research || result;
  return {
    answer: research.decision?.decision || research.answer || "",
    data: result.marketData || null,
    reports: research.decision?.analystResults || [],
    traces: research.traces || [],
    evidence: research.evidence || [],
  };
}
/** 上一轮背景仅限同范围已完成回答；不将资料问答混入市场历史证据。 */
export function buildFollowup(question, scope, turns = []) {
  const previous = turns
    .filter((turn) => turn.scope === scope && turn.status === "COMPLETED")
    .at(-1);
  if (!previous) return question;
  return `上轮讨论仅作背景，请根据本次证据重新核验。\n上轮问题：${previous.question.slice(0, 300)}\n上轮结论：${normalizeResult(previous.result).answer.slice(0, 1000)}\n本轮问题：${question}`;
}
/** 来源只允许绝对 HTTP(S)，防止上游链接变成脚本或本地文件入口。 */
export function safeUrl(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}
/** 根据真实事件构造阶段，取消与失败不把剩余阶段伪装成完成。 */
export function stageStates(events = [], scope = "MARKET", status = "RUNNING") {
  const list =
    scope === "DOCUMENTS"
      ? [
          ["RETRIEVAL", "检索资料"],
          ["ANSWER", "生成回答"],
        ]
      : [
          ["DATA", "获取资料"],
          ...Object.entries(roles).filter(
            ([key]) => key !== "PORTFOLIO_MANAGER",
          ),
          ["MANAGER", "经理汇总"],
        ];
  return list.map(([key, label]) => {
    const event = events
      .filter(
        (item) =>
          item.role === key ||
          (!item.role && item.phase === key) ||
          (key === "MANAGER" && item.phase === "MANAGER"),
      )
      .at(-1);
    let state =
      event?.status === "COMPLETED"
        ? "done"
        : event?.status === "STARTED"
          ? "running"
          : "waiting";
    if (terminal(status) && status !== "COMPLETED" && state === "running")
      state = "stopped";
    return { key, label, state };
  });
}
/** 图表只用有效实际收盘价；空数据返回null，等价序列居中，绝不生成演示曲线。 */
export function chartGeometry(bars, width = 600, height = 154) {
  const valid = bars.filter(
    (bar) => Number.isFinite(Number(bar.close)) && Number(bar.close) > 0,
  );
  if (!valid.length) return null;
  const values = valid.map((bar) => Number(bar.close)),
    low = Math.min(...values),
    high = Math.max(...values);
  const pad = high === low ? Math.max(high * 0.02, 1) : (high - low) * 0.12;
  const min = low - pad,
    max = high + pad;
  const points = values.map((price, index) => ({
    x: valid.length === 1 ? width / 2 : (index / (valid.length - 1)) * width,
    y: height - ((price - min) / (max - min)) * height,
  }));
  return {
    points,
    path: points
      .map(
        (point, index) =>
          `${index ? "L" : "M"}${point.x.toFixed(2)},${point.y.toFixed(2)}`,
      )
      .join(" "),
    min,
    max,
    first: valid[0].date,
    last: valid.at(-1).date,
    close: values.at(-1),
  };
}

/** 终态不可回退，防止取消后迟到的GET快照重新显示为运行中。 */
export function canApplyTaskView(current, incoming) {
  return !terminal(current) || current === incoming;
}

/** 服务端归档是权威状态，恢复只映射数据，不自动发送模型请求。 */
export function restoreTurns(rows = []) {
  return rows.map((row) => ({
    ...row.task,
    id: row.id,
    taskId: row.taskId,
    question: row.question,
    scope: row.scope,
  }));
}

/** MySQL缓存使用独立命名空间，首次切换不会覆盖尚未迁移的浏览器旧记录。 */
export function persistBrowserCache(
  storage,
  mode,
  payload,
  previousMode = mode,
) {
  const key =
    mode === "MYSQL" || previousMode === "MYSQL"
      ? "stock-research-workspace-mysql-v1"
      : "stock-research-workspace-v1";
  storage.setItem(key, JSON.stringify(payload));
}

/** 存储模式切换须先完成对应历史加载；活动任务结束前不向新模式发起研究。 */
export function historySubmissionAllowed(mode, cachedMode, ready) {
  return mode === cachedMode && (mode !== "MYSQL" || ready);
}

/** SSE和GET共用版本闸门；旧归档没有version时按0兼容。 */
export function canApplySnapshot(current, incoming) {
  return canApplyTaskView(current.status, incoming.status) &&
    Number(incoming.version || 0) >= Number(current.version || 0);
}
