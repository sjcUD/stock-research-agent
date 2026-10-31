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
