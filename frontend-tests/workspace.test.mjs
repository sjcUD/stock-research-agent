import test from "node:test";
import assert from "node:assert/strict";
import {
  chartGeometry,
  buildFollowup,
  safeUrl,
  stageStates,
  normalizeResult,
  canApplyTaskView,
} from "../stock-agent-web/src/main/resources/static/assets/workspace.js";

test("empty and flat charts never invent data or produce NaN", () => {
  assert.equal(chartGeometry([]), null);
  const chart = chartGeometry([
    { date: "2026-10-29", close: 10 },
    { date: "2026-10-30", close: 10 },
  ]);
  assert.equal(chart.points.length, 2);
  assert.ok(!chart.path.includes("NaN"));
  assert.equal(chartGeometry([{ close: "bad" }]), null);
});
test("follow-up uses same scope only, with bounded background", () => {
  const turns = [
    {
      scope: "DOCUMENTS",
      status: "COMPLETED",
      question: "资料问题",
      result: { answer: "不可混入市场的资料" },
    },
  ];
  assert.equal(buildFollowup("看风险", "MARKET", turns), "看风险");
  turns.push({
    scope: "MARKET",
    status: "COMPLETED",
    question: "看趋势",
    result: { research: { decision: { decision: "已完成市场结论" } } },
  });
  assert.ok(
    buildFollowup("看风险", "MARKET", turns).includes("已完成市场结论"),
  );
  assert.ok(!buildFollowup("看风险", "MARKET", turns).includes("不可混入市场"));
  assert.ok(
    buildFollowup("问题", "MARKET", [
      {
        scope: "MARKET",
        status: "COMPLETED",
        question: "旧",
        result: { answer: "a".repeat(5000) },
      },
    ]).length < 1500,
  );
});
test("external sources reject script, data and relative URLs", () => {
  for (const value of [
    "javascript:alert(1)",
    "data:text/html,x",
    "//evil.test",
    "/local",
  ])
    assert.equal(safeUrl(value), null);
  assert.equal(safeUrl("https://example.com/news"), "https://example.com/news");
});
test("progress uses events and never marks unfinished roles complete", () => {
  const steps = stageStates(
    [
      { phase: "DATA", role: "", status: "COMPLETED" },
      { phase: "ANALYST", role: "NEWS", status: "STARTED" },
    ],
    "MARKET",
    "RUNNING",
  );
  assert.equal(steps.find((x) => x.key === "NEWS").state, "running");
  assert.equal(steps.find((x) => x.key === "RISK").state, "waiting");
  assert.equal(
    stageStates(
      [{ phase: "ANALYST", role: "NEWS", status: "STARTED" }],
      "MARKET",
      "CANCELLED",
    ).find((x) => x.key === "NEWS").state,
    "stopped",
  );
});
test("result normalization preserves actual reports and evidence", () => {
  const result = {
    marketData: { snapshot: { symbol: "600519" }, news: { items: [] } },
    research: {
      decision: {
        decision: "报告",
        analystResults: [{ role: "NEWS", conclusion: "新闻" }],
      },
      traces: [],
      evidence: [],
    },
  };
  assert.equal(normalizeResult(result).answer, "报告");
  assert.equal(normalizeResult(result).reports.length, 1);
});

test("late snapshots cannot undo a terminal cancellation or completion", () => {
  assert.equal(canApplyTaskView("CANCELLED", "RUNNING"), false);
  assert.equal(canApplyTaskView("COMPLETED", "QUEUED"), false);
  assert.equal(canApplyTaskView("RUNNING", "COMPLETED"), true);
});

test("server interruption is terminal and history preserves original question", async () => {
  const { terminal, restoreTurns } = await import(
    "../stock-agent-web/src/main/resources/static/assets/workspace.js"
  );
  assert.equal(terminal("INTERRUPTED"), true);
  assert.equal(terminal("REJECTED"), true);
  const turns = restoreTurns([
    {
      id: "t",
      taskId: "t",
      question: "用户原问题",
      scope: "DOCUMENTS",
      task: {
        id: "t",
        status: "INTERRUPTED",
        error: "服务重启",
        events: [],
        result: null,
      },
    },
  ]);
  assert.equal(turns[0].question, "用户原问题");
  assert.equal(turns[0].status, "INTERRUPTED");
  assert.equal(turns[0].taskId, "t");
});

test("saving MySQL cache does not overwrite browser-only history", async () => {
  const { persistBrowserCache } = await import(
    "../stock-agent-web/src/main/resources/static/assets/workspace.js"
  );
  const values = new Map([
    [
      "stock-research-workspace-v1",
      JSON.stringify({
        sessions: [{ id: "legacy", turns: [{ question: "旧记录" }] }],
      }),
    ],
  ]);
  const original = values.get("stock-research-workspace-v1");
  persistBrowserCache({ setItem: (k, v) => values.set(k, v) }, "MYSQL", {
    sessions: [{ id: "server" }],
  });
  assert.equal(values.get("stock-research-workspace-v1"), original);
  assert.equal(
    JSON.parse(values.get("stock-research-workspace-mysql-v1")).sessions[0].id,
    "server",
  );
});

test("storage-mode changes during an active task cannot overwrite legacy history or allow new submissions", async () => {
  const { persistBrowserCache, historySubmissionAllowed } = await import(
    "../stock-agent-web/src/main/resources/static/assets/workspace.js"
  );
  for (const [configured, cached] of [
    ["MYSQL", "MEMORY"],
    ["MEMORY", "MYSQL"],
  ]) {
    const values = new Map([
      ["stock-research-workspace-v1", "legacy-unchanged"],
    ]);
    persistBrowserCache(
      { setItem: (k, v) => values.set(k, v) },
      configured,
      { sessions: [] },
      cached,
    );
    assert.equal(values.get("stock-research-workspace-v1"), "legacy-unchanged");
    assert.equal(historySubmissionAllowed(configured, cached, true), false);
  }
  assert.equal(historySubmissionAllowed("MYSQL", "MYSQL", false), false);
  assert.equal(historySubmissionAllowed("MYSQL", "MYSQL", true), true);
});
