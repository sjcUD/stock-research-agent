import { marked } from "../vendor/marked.js";
import DOMPurify from "../vendor/purify.js";
import { api, TaskChannel, TaskEventChannel } from "./api.js";
import {
  roles,
  terminal,
  normalizeResult,
  buildFollowup,
  safeUrl,
  stageStates,
  chartGeometry,
  canApplySnapshot,
  restoreTurns,
  persistBrowserCache,
  historySubmissionAllowed,
} from "./workspace.js";

/** MySQL模式从服务端恢复历史；浏览器仅保留有限离线缓存，旧缓存不会自动导入数据库。 */
const KEY = "stock-research-workspace-v1",
  MYSQL_KEY = "stock-research-workspace-mysql-v1",
  $ = (selector) => document.querySelector(selector);
let sessions = [],
  documents = [],
  current = null,
  config = null,
  active = null,
  toastTimer,
  uploadBusy = false,
  historyReady = false,
  historyLoading = false,
  historyOffset = 0,
  hasMoreHistory = false,
  cachedMode = "MEMORY",
  legacyHistoryAvailable = false;
try {
  const legacy = JSON.parse(localStorage.getItem(KEY) || "{}");
  legacyHistoryAvailable =
    Array.isArray(legacy.sessions) && legacy.sessions.length > 0;
  const mysqlSaved = localStorage.getItem(MYSQL_KEY);
  cachedMode = mysqlSaved ? "MYSQL" : "MEMORY";
  const saved = mysqlSaved ? JSON.parse(mysqlSaved) : legacy;
  sessions = Array.isArray(saved.sessions) ? saved.sessions.slice(0, 8) : [];
  documents = Array.isArray(saved.documents) ? saved.documents : [];
} catch {}
const icon = (name) => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("icon");
  const use = document.createElementNS(svg.namespaceURI, "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
};
/** 统一用 textContent 写入用户、供应商和错误文本，HTML仅在净化后的Markdown边界出现。 */
/** 创建安全文本节点；不把动态字符串当作HTML。 */
function el(tag, className = "", text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}
/** 创建动作按钮并绑定明确的用户操作。 */
function button(text, action, className = "") {
  const node = el("button", className, text);
  node.type = "button";
  node.addEventListener("click", action);
  return node;
}
/** 绘制与已确认v6一致的研股标记。 */
function mark() {
  const node = el("span", "brandmark");
  for (let i = 0; i < 3; i++) node.append(el("i"));
  node.setAttribute("aria-hidden", "true");
  return node;
}
/** 显示短暂反馈，不阻断正在进行的研究。 */
function toast(text) {
  clearTimeout(toastTimer);
  $("#toast").textContent = text;
  $("#toast").hidden = false;
  toastTimer = setTimeout(() => {
    $("#toast").hidden = true;
  }, 3500);
}
/** 只保留有限会话和轮次，容量不足时保留当前页面并明确提示。 */
function save() {
  try {
    persistBrowserCache(
      localStorage,
      config?.historyStorage || cachedMode,
      {
        sessions: sessions
          .slice(0, 8)
          .map((session) => ({ ...session, turns: session.turns.slice(-10) })),
        documents: documents
          .filter((item) => item.status === "INDEXED")
          .slice(0, 30),
      },
      cachedMode,
    );
    $("#save-state").textContent =
      cachedMode === "MYSQL"
        ? "服务端历史 · 本地缓存"
        : "浏览器缓存 · 进程内任务";
  } catch {
    $("#save-state").textContent = "仅当前页面";
  }
}
/** 旧记录仅本地导出，不自动上传、覆盖或转换为已入库消息。 */
function exportLegacyHistory() {
  const content = localStorage.getItem(KEY);
  if (!content) {
    toast("没有可导出的浏览器旧记录。");
    return;
  }
  const url = URL.createObjectURL(
    new Blob([content], { type: "application/json" }),
  );
  const link = el("a");
  link.href = url;
  link.download = "stock-research-browser-history.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
/** 关闭移动导航并移除隐藏区域的键盘焦点入口。 */
function closeSidebar() {
  $("#sidebar").classList.remove("open");
  $("#sidebar").inert = window.matchMedia("(max-width:760px)").matches;
}
/** 开始新的证券/日期上下文，进行中任务必须先结束。 */
function newSession() {
  if (active || historyLoading) {
    toast("请先完成当前操作。");
    return;
  }
  current = {
    id: crypto.randomUUID(),
    symbol: config?.defaultSymbol || "600519",
    asOf: config?.defaultAsOf || "2026-10-30",
    turns: [],
    scope: "MARKET",
  };
  $("#scope").value = "MARKET";
  $("#question").value = "";
  closeSidebar();
  render();
  $("#question").focus();
}
/** 只展示当前浏览器实际保存的历史，不生成演示记录。 */
function history() {
  const container = $("#history");
  container.replaceChildren();
  if (config?.historyStorage === "MYSQL" && legacyHistoryAvailable)
    container.append(button("导出浏览器旧记录", exportLegacyHistory));
  const query = $("#history-filter").value.trim().toLowerCase();
  const list = sessions.filter((session) =>
    `${session.symbol} ${session.title || session.turns[0]?.question || ""}`
      .toLowerCase()
      .includes(query),
  );
  if (!list.length) {
    container.append(
      el(
        "p",
        "history-empty",
        query ? "没有匹配的研究记录。" : "你的研究对话会出现在这里。",
      ),
    );
    return;
  }
  for (const session of list) {
    const node = button(
      session.title || session.turns[0]?.question || "研究对话",
      async () => {
        if (active || historyLoading) {
          toast("请先完成或停止当前研究。");
          return;
        }
        historyLoading = true;
        setBusy();
        try {
          if (session.remote && !session.loaded) await loadSession(session);
          current = session;
          closeSidebar();
        } catch (error) {
          toast(error.message);
        } finally {
          historyLoading = false;
          render();
        }
        const pending = current?.turns.find(
          (turn) => !terminal(turn.status) && turn.taskId,
        );
        if (pending) reconnect(pending);
      },
      `history-item${current?.id === session.id ? " active" : ""}`,
    );
    node.prepend(el("small", "", `${session.symbol} / ${session.asOf}`));
    container.append(node);
  }
  if (config?.historyStorage === "MYSQL" && hasMoreHistory) {
    container.append(
      button("加载更多历史", async () => {
        if (active || historyLoading) return;
        historyLoading = true;
        setBusy();
        try {
          const page = await api.conversations(historyOffset);
          historyOffset += page.conversations.length;
          hasMoreHistory = page.conversations.length === 50;
          for (const meta of page.conversations)
            if (!sessions.some((s) => s.id === meta.id))
              sessions.push({
                ...meta,
                turns: [],
                remote: true,
                loaded: false,
              });
        } catch (error) {
          toast(error.message);
        } finally {
          historyLoading = false;
          render();
        }
      }),
    );
  }
}
/** Markdown先解析再净化，并禁用远程图片/iframe，避免报告读取触发外部追踪请求。 */
function markdown(text) {
  const node = el("div", "markdown");
  node.innerHTML = DOMPurify.sanitize(
    marked.parse(String(text || ""), { gfm: true, breaks: true }),
    {
      ALLOWED_TAGS: [
        "p",
        "br",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "strong",
        "b",
        "em",
        "i",
        "del",
        "s",
        "blockquote",
        "ul",
        "ol",
        "li",
        "hr",
        "pre",
        "code",
        "table",
        "thead",
        "tbody",
        "tfoot",
        "tr",
        "th",
        "td",
        "a",
      ],
      ALLOWED_ATTR: ["href", "title", "start", "colspan", "rowspan"],
    },
  );
  for (const a of node.querySelectorAll("a")) {
    const url = safeUrl(a.getAttribute("href"));
    if (!url) {
      a.removeAttribute("href");
    } else {
      a.href = url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
    }
  }
  for (const table of node.querySelectorAll("table")) {
    const wrapper = el("div", "table-wrap");
    table.before(wrapper);
    wrapper.append(table);
  }
  return node;
}
/** 统一同步任务状态、输入锁定和取消按钮。 */
function setBusy() {
  const busy = !!active || historyLoading;
  $("#cancel").hidden = !active;
  $("#send").hidden = busy;
  $("#send").disabled =
    !config?.researchConfigured ||
    !$("#question").value.trim() ||
    !historySubmissionAllowed(
      config?.historyStorage || cachedMode,
      cachedMode,
      historyReady,
    );
  $("#scope").disabled = busy;
  $("#question").disabled = busy;
  const locked = (current?.turns.length || 0) > 0 || busy;
  $("#symbol").disabled = locked || config?.dataMode === "SAMPLE";
  $("#as-of").disabled = locked || config?.dataMode === "SAMPLE";
  $("#context-lock").hidden = !locked;
  $("#cancel").disabled = !!active?.channel.cancelRequested;
  $("#cancel").textContent = active?.channel.cancelRequested
    ? "正在停止…"
    : "停止研究";
}
/** 由会话状态重建对话展示，保留股票和日期。 */
function render() {
  if (!current) return;
  $("#scope").value = current.scope || current.turns.at(-1)?.scope || "MARKET";
  $("#symbol").value = current.symbol;
  $("#as-of").value = current.asOf;
  $("#as-of").max = config?.maxAsOf || "";
  $("#conversation-title").textContent = current.turns.length
    ? `${current.symbol}，${current.turns[0].question.slice(0, 15)}`
    : "新的研究对话";
  $("#welcome").hidden = !!current.turns.length;
  $("#followups").hidden =
    !current.turns.some((turn) => turn.status === "COMPLETED") || !!active;
  $("#messages").replaceChildren(...current.turns.map(renderTurn));
  $("#full-report").disabled = !lastCompleted();
  history();
  scopeHint();
}
/** 定位当前会话最近一次真实成功报告。 */
function lastCompleted() {
  return current?.turns.filter((turn) => turn.status === "COMPLETED").at(-1);
}
/** 把服务端状态映射为用户可读文案。 */
function resultSummary(turn) {
  const names = {
    QUEUED: "已进入研究队列",
    COMPLETED:
      turn.scope === "DOCUMENTS"
        ? "资料问答已完成"
        : "已完成研究，综合5位分析师的观点",
    CANCELLED: "研究已停止",
    FAILED: "本次研究未完成",
    TIMED_OUT: "研究已超时",
    INTERRUPTED: "研究因服务重启中断",
    REJECTED: "研究未进入执行队列",
    DISCONNECTED: "连接已断开，可恢复任务状态",
  };
  if (names[turn.status]) return names[turn.status];
  return (
    {
      DATA: "正在获取证券资料",
      ANALYST: "分析师正在独立研究",
      MANAGER: "组合经理正在汇总",
      RETRIEVAL: "正在检索相关资料",
      ANSWER: "正在生成资料回答",
    }[turn.phase] || "正在准备研究"
  );
}
/** 折叠展示真实事件驱动的阶段，不估造百分比。 */
function progress(turn) {
  const details = el("details", "run-progress");
  details.open = !!turn.progressOpen;
  details.addEventListener("toggle", () => {
    turn.progressOpen = details.open;
  });
  const summary = el("summary");
  summary.append(
    el(
      "span",
      `status-dot ${["QUEUED", "RUNNING"].includes(turn.status) ? "running" : ["FAILED", "TIMED_OUT", "DISCONNECTED", "INTERRUPTED", "REJECTED"].includes(turn.status) ? "failed" : ""}`,
    ),
    el("span", "", resultSummary(turn)),
    icon("chevron"),
  );
  details.append(summary);
  const stages = el("div", "stage-grid");
  for (const stage of stageStates(turn.events || [], turn.scope, turn.status)) {
    const node = el("span", `stage ${stage.state}`);
    node.append(
      el(
        "span",
        "stage-indicator",
        stage.state === "done" ? "✓" : stage.state === "stopped" ? "−" : "",
      ),
      el("span", "", stage.label),
    );
    stages.append(node);
  }
  details.append(stages);
  return details;
}
/** 渲染一轮问题与成功、失败或取消状态。 */
function renderTurn(turn) {
  const node = el("article", "turn");
  node.dataset.id = turn.id;
  node.append(
    el(
      "div",
      "turn-meta",
      `${current.asOf}　${current.symbol}　${turn.scope === "DOCUMENTS" ? "资料问答" : "市场研究"}`,
    ),
    el("div", "user-message", turn.question),
  );
  const identity = el("div", "assistant-identity");
  identity.append(mark(), el("span", "", "研股"), el("small", "", "研究助手"));
  node.append(identity, progress(turn));
  if (turn.traces?.length)
    node.append(
      button(`查看已完成角色轨迹（${turn.traces.length}）`, () =>
        showTraces(turn),
      ),
    );
  if (turn.draftAnswer && turn.status !== "COMPLETED") {
    const draft = el("section", "stream-draft");
    draft.append(el("small", "", terminal(turn.status) ? "未完成草稿 · 不作为研究结论" : "正在生成 · 未完成草稿"),
      el("div", "stream-draft-body", turn.draftAnswer));
    node.append(draft);
  }
  if (turn.status === "COMPLETED") {
    const result = normalizeResult(turn.result);
    node.append(markdown(result.answer));
    if (result.data) node.append(marketCard(result.data, turn));
    const tools = el("div", "result-tools");
    if (result.reports.length) {
      const reports = button("", () => showReports(turn));
      reports.append(
        icon("chat"),
        el("span", "", "分析师的报告与分歧"),
        el("span", "hint", "查看5位分析师"),
        icon("chevron"),
      );
      tools.append(reports);
    }
    const sources = button("", () => showSources(turn));
    sources.append(
      icon("file"),
      el("span", "", "来源与证据"),
      el(
        "span",
        "hint",
        result.data
          ? "查看日期、来源与覆盖范围"
          : `${result.evidence.length}个检索片段`,
      ),
      icon("chevron"),
    );
    tools.append(sources);
    node.append(tools);
    const chips = el("div", "source-chips");
    if (result.data) {
      chips.append(
        el("span", "source-chip", "腾讯日线"),
        el("span", "source-chip", `新闻：${feedLabel(result.data.news)}`),
        el(
          "span",
          "source-chip",
          `公告：${feedLabel(result.data.announcements)}`,
        ),
      );
      if (result.data.mode === "SAMPLE")
        chips.firstChild.textContent = "合成样例";
    } else {
      chips.append(
        el("span", "source-chip", `${result.evidence.length}个资料片段`),
      );
    }
    node.append(chips);
    const actions = el("div", "answer-actions");
    const copy = button("复制", async () => {
      try {
        await navigator.clipboard.writeText(result.answer);
        toast("已复制回答");
      } catch {
        toast("浏览器未允许复制，请手动选择文本。");
      }
    });
    copy.prepend(icon("file"));
    const download = button("下载报告", () => downloadReport(turn));
    download.prepend(icon("download"));
    actions.append(copy, download);
    node.append(actions);
  } else if (
    [
      "FAILED",
      "TIMED_OUT",
      "CANCELLED",
      "DISCONNECTED",
      "INTERRUPTED",
      "REJECTED",
    ].includes(turn.status)
  ) {
    const box = el(
      "div",
      "error-panel",
      turn.error ||
        (turn.status === "CANCELLED"
          ? "已向后台发送停止请求，未发布未完成的结论。"
          : "本次研究未完成。"),
    );
    if (turn.status === "DISCONNECTED" && turn.taskId)
      box.append(button("重新连接此任务", () => reconnect(turn)));
    else box.append(button("重新研究", () => retry(turn)));
    if (turn.status !== "DISCONNECTED")
      box.append(
        button("新建研究调整范围", () => {
          newSession();
          $("#scope").value = turn.scope;
          $("#question").value = turn.question;
          scopeHint();
          $("#symbol").focus();
        }),
      );
    node.append(box);
  } else {
    node.append(
      el(
        "p",
        "loading-message",
        turn.scope === "DOCUMENTS"
          ? "正在检索资料并生成回答，完成后保留原文片段。"
          : "正在核对行情、指标与资讯；可展开研究过程查看实际进度。",
      ),
    );
  }
  return node;
}
/** 区分已有记录、成功空结果和上游不可用。 */
function feedLabel(feed) {
  return feed?.status === "AVAILABLE"
    ? `${feed.items.length}条记录`
    : feed?.status === "EMPTY"
      ? "窗口内无结果"
      : "暂不可用";
}
/** 图表只消费后端日线，不添加假基准、不预测缺失价格。 */
function marketCard(data, turn) {
  const card = el("section", "market-card");
  const header = el("div", "market-card-header"),
    security = el("div", "security-name"),
    title = el("div");
  title.append(
    el("strong", "", data.snapshot.symbol),
    el(
      "small",
      "",
      `${data.mode === "SAMPLE" ? "合成样例" : "沪深A股"} / ${data.snapshot.asOf}`,
    ),
  );
  security.append(el("span", "security-icon", "A股"), title);
  const ranges = el("div", "chart-ranges");
  const chartWrap = el("div"),
    svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.classList.add("price-chart");
  svg.setAttribute("viewBox", "0 0 600 154");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", `${data.snapshot.symbol}实际返回日线收盘价`);
  const labels = el("div", "chart-labels");
  chartWrap.append(svg, labels);
  const bars = data.history.items;
  function draw(count) {
    const selected = count ? bars.slice(-count) : bars;
    const geometry = chartGeometry(selected);
    svg.replaceChildren();
    labels.replaceChildren();
    if (!geometry) {
      labels.append(el("span", "", "暂无有效日线"));
      return;
    }
    for (let i = 1; i <= 3; i++) {
      const line = document.createElementNS(svg.namespaceURI, "path");
      line.setAttribute("d", `M0 ${i * 38}H600`);
      line.setAttribute("stroke", "#e9f4fa");
      line.setAttribute("fill", "none");
      svg.append(line);
    }
    const area = document.createElementNS(svg.namespaceURI, "path");
    area.setAttribute(
      "d",
      `${geometry.path} L${geometry.points.at(-1).x},154 L${geometry.points[0].x},154 Z`,
    );
    area.setAttribute("fill", "#edf8ff");
    svg.append(area);
    const path = document.createElementNS(svg.namespaceURI, "path");
    path.setAttribute("d", geometry.path);
    path.setAttribute("fill", "none");
    path.setAttribute("stroke", "#229fdf");
    path.setAttribute("stroke-width", "2");
    path.setAttribute("vector-effect", "non-scaling-stroke");
    svg.append(path);
    labels.append(
      el("span", "", geometry.first),
      el("span", "", geometry.last),
    );
    for (const item of ranges.children)
      item.classList.toggle("selected", Number(item.dataset.count) === count);
  }
  for (const [label, count] of [
    ["20日", 20],
    ["60日", 60],
    ["全部", 0],
  ]) {
    const choice = button(label, () => draw(count));
    choice.dataset.count = count;
    ranges.append(choice);
  }
  header.append(security, ranges);
  const body = el("div", "chart-body"),
    stats = el("div", "chart-stat");
  stats.append(
    el("small", "", "最近收盘"),
    el("div", "current-price", Number(data.snapshot.close).toFixed(2)),
    el("div", "currency", "人民币 / 未复权"),
  );
  const first = Number(bars[0]?.close),
    last = Number(data.snapshot.close);
  if (first > 0) {
    const change = (last / first - 1) * 100;
    stats.append(
      el(
        "div",
        `price-change ${change >= 0 ? "positive" : "negative"}`,
        `${change >= 0 ? "+" : ""}${change.toFixed(2)}%`,
      ),
      el("small", "", "窗口价格变化"),
    );
  }
  body.append(chartWrap, stats);
  const indicators = el("div", "indicator-row");
  for (const [key, label] of [
    ["SMA5", "5日均线"],
    ["SMA20", "20日均线"],
    ["RSI14", "RSI 14"],
    ["MACD_DIF", "MACD DIF"],
  ]) {
    const value = el("div");
    value.append(
      el("small", "", label),
      el(
        "strong",
        "",
        data.indicators[key] === undefined
          ? "数据不足"
          : Number(data.indicators[key]).toFixed(2),
      ),
    );
    indicators.append(value);
  }
  const footer = el("div", "market-footer");
  footer.append(
    el(
      "span",
      "",
      data.mode === "SAMPLE"
        ? "合成日历天样例，不代表实际交易日"
        : "实际采集日线，未复权；指标受窗口初值影响",
    ),
    button("查看数据来源", () => showSources(turn)),
  );
  card.append(header, body, indicators, footer);
  draw(60);
  return card;
}
/** 用原生模态面板承载临时详情，保持主界面两栏。 */
function openDrawer(title, content) {
  $("#drawer-title").textContent = title;
  $("#drawer-content").replaceChildren(content);
  if (!$("#drawer").open) $("#drawer").showModal();
}
/** 失败研究也能查看已完成角色；只展示真实回调记录，不为失败角色伪造正文。 */
function showTraces(turn) {
  const body = el("div");
  for (const trace of turn.traces || []) {
    const item = el("details", "trace-input");
    item.append(
      el(
        "summary",
        "",
        `${roles[trace.role] || trace.role} / ${trace.elapsedMillis}ms`,
      ),
      el("h4", "", "实际输入"),
      el("pre", "", trace.input),
      el("h4", "", "实际输出"),
      el("pre", "", trace.output),
    );
    body.append(item);
  }
  openDrawer("已完成角色的执行轨迹", body);
}
/** 切换组合经理和角色报告，按需展开原始执行输入。 */
function showReports(turn, initial = "PORTFOLIO_MANAGER") {
  const value = normalizeResult(turn.result),
    wrapper = el("div"),
    tabs = el("div", "report-tabs"),
    body = el("div");
  const reports = value.reports.length
    ? [
        { role: "PORTFOLIO_MANAGER", conclusion: value.answer },
        ...value.reports,
      ]
    : [{ role: "DOCUMENT_ANSWER", conclusion: value.answer }];
  function select(role) {
    for (const child of tabs.children)
      child.classList.toggle("selected", child.dataset.role === role);
    const report = reports.find((item) => item.role === role);
    body.replaceChildren(markdown(report.conclusion));
    const trace = value.traces.find((item) => item.role === role);
    if (trace) {
      const detail = el("details", "trace-input");
      detail.append(
        el(
          "summary",
          "",
          `查看此角色输入 / ${(trace.elapsedMillis / 1000).toFixed(1)}秒`,
        ),
        el("pre", "", trace.input),
      );
      body.append(detail);
    }
  }
  for (const report of reports) {
    const tab = button(
      roles[report.role] ||
        (report.role === "DOCUMENT_ANSWER" ? "资料回答" : report.role),
      () => select(report.role),
    );
    tab.dataset.role = report.role;
    tabs.append(tab);
  }
  wrapper.append(tabs, body);
  select(
    reports.some((report) => report.role === initial)
      ? initial
      : reports[0].role,
  );
  openDrawer("完整研究报告", wrapper);
}
/** 安全展示单个来源及可核验的HTTP原文链接。 */
function sourceItem(title, body, link, small) {
  const item = el("article", "source-item");
  item.append(el("h3", "", title), el("p", "", body));
  const url = safeUrl(link);
  if (url) {
    const anchor = el("a", "", "打开来源原文");
    anchor.href = url;
    anchor.target = "_blank";
    anchor.rel = "noopener noreferrer";
    item.append(anchor);
  }
  if (small) item.append(el("small", "", small));
  return item;
}
/** 同时展示证据内容、日期、覆盖范围和来源指纹。 */
function showSources(turn) {
  const result = normalizeResult(turn.result),
    wrapper = el("div");
  wrapper.append(
    el(
      "p",
      "help",
      "这里列出本次采集或检索的来源，不代表模型已逐条核验。请结合日期和覆盖范围阅读。",
    ),
  );
  if (result.data) {
    const data = result.data;
    for (const [name, feed] of [
      ["日线", data.history],
      ["新闻", data.news],
      ["公告", data.announcements],
    ]) {
      wrapper.append(
        sourceItem(
          `${name} / ${feedLabel(feed)}`,
          `来源：${data.mode === "SAMPLE" ? "SAMPLE 合成样例" : feed.source}\n实际行情日：${data.snapshot.asOf}\n抓取时间：${feed.fetchedAt}\n口径：${feed.detail}`,
          null,
          `响应版本：${feed.version || "未获得响应"}${feed.limitedHistory ? "；仅最近窗口，不是完整历史" : ""}`,
        ),
      );
      for (const item of feed.items || []) {
        if (name === "日线") break;
        wrapper.append(
          sourceItem(
            item.title,
            `${item.publishedAt} / ${item.publisher}\n${item.summary || "公告列表未包含全文。"}`,
            item.url,
            `条目ID：${item.id}`,
          ),
        );
      }
    }
  } else {
    wrapper.append(
      el(
        "p",
        "help",
        "资料问答未按历史披露时点过滤。PDF引用对应实体页；其他格式显示解析全文。OCR文字请对照原件复核。",
      ),
    );
    for (const hit of result.evidence) {
      const document = documents.find(
        (item) => item.documentId === hit.citation?.documentId,
      );
      const item = sourceItem(
        document?.filename || hit.citation?.documentId || "检索片段", hit.text, null,
        `片段：${hit.chunkId}；页/段：${hit.citation?.page || 1}；版本：${hit.citation?.revision || "未知"}；相似度：${Number(hit.score).toFixed(3)}`);
      if (hit.citation?.documentId && hit.citation?.revision)
        item.append(button("查看引用原文", () => previewDocument(hit.citation, hit.text)));
      wrapper.append(item);
    }
    if (!result.evidence.length)
      wrapper.append(el("p", "empty-list", "本次没有检索到可展示的资料片段。"));
  }
  openDrawer("来源与证据", wrapper);
}
/** 原文文本始终使用文本节点；仅完全匹配的页内偏移标记引用。 */
async function previewDocument(citation, fragment = "") {
  const wrapper = el("div", "document-preview");
  wrapper.append(el("p", "help", "正在读取已归档原文…"));
  openDrawer("原文预览", wrapper);
  try {
    const id = citation.documentId, revision = citation.revision;
    const meta = await api.document(id, revision);
    let page = Math.min(Math.max(Number(citation.page) || 1, 1), meta.pageCount);
    const heading = el("p", "help", `${meta.status} · ${meta.physicalPages ? "PDF实体页" : "解析全文（不表示原文件物理页）"} · OCR ${meta.ocrPages}页`);
    const controls = el("div", "preview-controls");
    const pageLabel = el("span");
    const body = el("pre", "document-page");
    const file = el("a", "", meta.physicalPages ? "在新窗口打开PDF原件" : "下载原件");
    file.href = api.documentFile(id, revision); file.target = "_blank"; file.rel = "noopener";
    let frame;
    if (meta.physicalPages) {
      frame = el("iframe", "pdf-preview"); frame.title = "PDF原件";
      frame.src = `${file.href}#page=${page}`;
    }
    let requestVersion = 0;
    const load = async () => {
      const version = ++requestVersion, requested = page;
      pageLabel.textContent = `${page} / ${meta.pageCount}`;
      if (frame) frame.src = `${file.href}#page=${page}`;
      body.textContent = "正在读取…";
      try {
        const parsed = await api.page(id, revision, requested);
        if (version !== requestVersion) return;
        body.replaceChildren();
        const offset = Number(citation.offset);
        if (fragment && requested === citation.page && Number.isInteger(offset) && offset >= 0 && parsed.text.slice(offset, offset + fragment.length) === fragment) {
          body.append(document.createTextNode(parsed.text.slice(0, offset)), el("mark", "", fragment), document.createTextNode(parsed.text.slice(offset + fragment.length)));
        } else body.textContent = parsed.text || "此页没有可提取文字。";
        if (parsed.ocr) body.prepend(el("small", "ocr-notice", "OCR识别文字，请对照原件复核。\n"));
      } catch (error) { if (version === requestVersion) body.textContent = error.message; }
    };
    controls.append(button("上一页", () => { if (page > 1) { page--; load(); } }), pageLabel,
      button("下一页", () => { if (page < meta.pageCount) { page++; load(); } }), file);
    wrapper.replaceChildren(heading, controls, body);
    if (frame) wrapper.append(frame);
    await load();
  } catch (error) { wrapper.replaceChildren(el("p", "error-panel", error.message)); }
}
/** 导出真实报告及来源为Markdown文本。 */
function downloadReport(turn) {
  const result = normalizeResult(turn.result);
  let text = `# ${current.symbol} 研究报告\n\n研究日期：${current.asOf}\n问题：${turn.question}\n资料范围：${turn.scope === "DOCUMENTS" ? "资料问答" : "市场研究"}\n\n${result.answer}\n`;
  for (const report of result.reports)
    text += `\n## ${roles[report.role]}\n\n${report.conclusion}\n`;
  if (result.data) {
    text += `\n## 数据口径\n\n${result.data.mode}；实际行情日 ${result.data.snapshot.asOf}；未复权日线。\n`;
    for (const feed of [
      result.data.history,
      result.data.news,
      result.data.announcements,
    ]) {
      text += `\n来源：${feed.source}\n抓取时间：${feed.fetchedAt}\n版本：${feed.version}\n状态：${feed.status}；${feed.detail}\n`;
      for (const item of feed.items || [])
        if (item.url) text += `\n- ${item.title}：${item.url}\n`;
    }
  } else
    for (const hit of result.evidence)
      text += `\n## 片段 ${hit.chunkId}\n\n${hit.text}\n`;
  const url = URL.createObjectURL(
    new Blob([text], { type: "text/markdown;charset=utf-8" }),
  );
  const a = el("a");
  a.href = url;
  a.download = `${current.symbol}-${current.asOf}-研究报告.md`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
/** 任务ID保存后再轮询；网络断开不会自动新建重复收费任务。 */
async function poll(turn, run) {
  let failures = 0;
  while (active === run) {
    let view;
    try {
      view = await api.task(turn.taskId);
      failures = 0;
    } catch (error) {
      if (active !== run) return;
      if (error.status === 404 || ++failures >= 3) throw error;
      await new Promise((resolve) => setTimeout(resolve, 1000));
      continue;
    }
    if (active !== run) return;
    applyView(turn, view);
    if (terminal(view.status)) return;
    await new Promise((resolve) => setTimeout(resolve, 600));
  }
}
/** SSE断流后读取同一任务，网络重连不会重复计费。 */
async function observe(turn, run) {
  run.stream = new TaskEventChannel(turn.taskId, (view) => {
    if (active === run) applyView(turn, view);
  });
  const completed = await run.stream.done;
  if (!completed && active === run && !terminal(turn.status)) await poll(turn, run);
}
/** 拒绝迟到快照将终态回退，并保存一致的状态。 */
function applyView(turn, view) {
  if (!canApplySnapshot(turn, view)) return;
  const draftOnly = turn.status === view.status && turn.phase === view.phase &&
    JSON.stringify(turn.events || []) === JSON.stringify(view.events || []) && !terminal(view.status);
  turn.version = view.version || 0;
  turn.draftAnswer = view.draftAnswer || "";
  if (terminal(view.status) && active?.turn === turn) active.stream?.close(true);
  turn.status = view.status;
  turn.phase = view.phase;
  turn.events = view.events || [];
  turn.traces = view.traces || [];
  turn.error = view.error || "";
  if (view.result) turn.result = view.result;
  if (draftOnly) {
    const article = [...document.querySelectorAll(".turn")].find((node) => node.dataset.id === turn.id);
    const body = article?.querySelector(".stream-draft-body");
    if (body) { body.textContent = turn.draftAnswer; return; }
  }
  save();
  render();
}
/** 先创建一次后台任务，再轮询同一ID，不自动重发收费请求。 */
async function submit(question, scope = $("#scope").value) {
  if (active || historyLoading || !question.trim()) return;
  if (
    !historySubmissionAllowed(
      config?.historyStorage || cachedMode,
      cachedMode,
      historyReady,
    )
  ) {
    toast("历史存储尚未连接，请先恢复连接并核对已有任务。");
    return;
  }
  if (!config?.researchConfigured) {
    toast("研究服务尚未配置，请先检查本地服务。");
    return;
  }
  if (scope === "DOCUMENTS" && !config.ragConfigured) {
    toast("资料索引尚未配置。");
    return;
  }
  if (!current.turns.length) {
    current.symbol = $("#symbol").value.trim();
    current.asOf = $("#as-of").value;
    if (!current.symbol || !current.asOf) {
      toast("请填写证券代码和研究日期。");
      return;
    }
    sessions.unshift(current);
    if (config?.historyStorage !== "MYSQL") sessions = sessions.slice(0, 8);
  }
  const requestQuestion = buildFollowup(question, scope, current.turns);
  const turn = {
    id: crypto.randomUUID(),
    question,
    scope,
    status: "QUEUED",
    phase: "QUEUE",
    events: [],
  };
  current.turns.push(turn);
  const run = { turn, channel: new TaskChannel() };
  active = run;
  $("#question").value = "";
  save();
  render();
  window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
  try {
    const view = await api.start({
      question: requestQuestion,
      displayQuestion: question,
      conversationId: current.id,
      symbol: current.symbol,
      asOf: current.asOf,
      scope,
      mode: "PARALLEL",
    });
    turn.taskId = view.id;
    current.remote = config?.historyStorage === "MYSQL";
    current.loaded = true;
    current.title ||= question.slice(0, 100);
    applyView(turn, view);
    const stopped = await run.channel.attach(view.id);
    if (stopped) applyView(turn, stopped);
    if (!terminal(turn.status)) await observe(turn, run);
  } catch (error) {
    if (!terminal(turn.status)) {
      turn.status = turn.taskId ? "DISCONNECTED" : "FAILED";
      turn.error = turn.taskId
        ? error.message
        : `${error.message} 尚未确认服务器是否已创建任务，请重新加载历史核对后再决定是否重试。`;
      save();
    }
  } finally {
    if (active === run) active = null;
    render();
  }
}
/** 将原问题填回输入框，由用户明确确认新的研究。 */
function retry(turn) {
  if (active) return;
  $("#scope").value = turn.scope;
  $("#question").value = turn.question;
  $("#question").focus();
  setBusy();
  toast("已填入原问题，确认后发送新的研究请求。");
}
/** 查询原任务ID恢复状态，不创建重复研究。 */
async function reconnect(turn) {
  if (active) return;
  const run = { turn, channel: new TaskChannel() };
  active = run;
  run.channel.id = turn.taskId;
  turn.status = "RUNNING";
  render();
  try {
    await observe(turn, run);
  } catch (error) {
    if (!terminal(turn.status)) {
      turn.status = error.status === 404 ? "FAILED" : "DISCONNECTED";
      turn.error = error.message;
    }
  } finally {
    active = null;
    save();
    render();
  }
}
/** 上传并等待索引确认，提供显式资料范围和检索操作。 */
async function library() {
  const wrapper = el("div");
  wrapper.append(
    el(
      "p",
      "help",
      "上传后可在“资料问答”中使用。市场研究仍只使用市场数据，不会自动混入上传内容。",
    ),
  );
  const input = el("input");
  input.type = "file";
  input.accept = ".pdf,.doc,.docx,.xls,.xlsx,.html,.htm,.txt,.md,.markdown,.png,.jpg,.jpeg";
  input.hidden = true;
  const upload = button("", () => input.click(), "library-upload");
  upload.append(
    el("span", "", uploadBusy ? "正在解析并建立索引…" : "选择研究文档"),
    el("small", "", "PDF、Word、Excel、HTML、TXT、Markdown、PNG、JPEG / 最大10MiB"),
  );
  upload.disabled = uploadBusy || !config?.ragConfigured;
  input.addEventListener("change", async () => {
    const file = input.files[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) {
      toast("文件超过10MiB，请缩小后重试。");
      return;
    }
    uploadBusy = true;
    const record = { filename: file.name, status: "INDEXING" };
    documents.unshift(record);
    library();
    try {
      Object.assign(record, await api.upload(file));
      save();
      toast(`索引完成，共${record.chunkCount}个片段。`);
    } catch (error) {
      record.status = "FAILED";
      record.error = error.message;
    } finally {
      uploadBusy = false;
      if ($("#drawer").open && $("#drawer-title").textContent === "研究资料")
        library();
    }
  });
  wrapper.append(upload, input, el("p", "help", config?.ocrAvailable
    ? "OCR已就绪：支持扫描PDF及PNG/JPEG。PDF最多100页，其中OCR最多20页；识别文字可能有误。"
    : "OCR尚未就绪：可上传文本PDF及Office等文档，扫描件需要配置本地OCR语言模型。"));
  if (!config?.ragConfigured)
    wrapper.append(el("p", "help", "资料索引服务尚未配置。"));
  if (!documents.length)
    wrapper.append(
      el(
        "p",
        "empty-list",
        "尚未上传研究资料。索引完成后可查看解析全文与原件；PDF保留实体页码。",
      ),
    );
  for (const record of documents) {
    const row = el("div", "document-row"),
      info = el("div");
    row.append(icon("file"));
    info.append(
      el("strong", "", record.filename),
      el(
        "small",
        "",
        record.status === "INDEXED"
          ? `可用于资料问答 / ${record.chunkCount}个片段`
          : record.status === "FAILED"
            ? record.error
            : "正在上传、解析和写入索引，等待服务器确认…",
      ),
    );
    row.append(info);
    if (record.documentId && record.revision)
      row.append(button("查看原文", () => previewDocument(record)));
    wrapper.append(row);
  }
  const switchScope = el("div", "library-scope");
  switchScope.append(
    button(
      "围绕资料提问",
      () => {
        if (active) {
          toast("请先完成当前研究。");
          return;
        }
        $("#scope").value = "DOCUMENTS";
        scopeHint();
        $("#drawer").close();
        $("#question").focus();
      },
      "primary-text",
    ),
  );
  wrapper.append(switchScope);
  const search = el("form", "library-search"),
    query = el("input");
  query.placeholder = "检索已索引资料";
  query.setAttribute("aria-label", "检索已索引资料");
  const searchButton = el("button", "primary-text", "检索"),
    hits = el("div");
  searchButton.type = "submit";
  search.append(query, searchButton);
  search.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!query.value.trim()) return;
    searchButton.disabled = true;
    hits.replaceChildren(el("p", "help", "正在检索…"));
    try {
      const result = await api.search(query.value);
      hits.replaceChildren();
      if (!result.hits?.length)
        hits.append(
          el("p", "empty-list", "没有检索到相关片段，可换个关键词。"),
        );
      for (const hit of result.hits || [])
        hits.append(
          sourceItem(
            documents.find(
              (item) => item.documentId === hit.citation?.documentId,
            )?.filename ||
              hit.citation?.documentId ||
              "资料片段",
            hit.text,
            null,
            `相似度 ${Number(hit.score).toFixed(3)}`,
          ),
        );
    } catch (error) {
      hits.replaceChildren(el("p", "help", error.message));
    } finally {
      searchButton.disabled = false;
    }
  });
  wrapper.append(search, hits);
  openDrawer("研究资料", wrapper);
}
/** 解释当前资料范围，市场与资料问答不可混用背景。 */
function scopeHint() {
  if (current) current.scope = $("#scope").value;
  const docs = $("#scope").value === "DOCUMENTS";
  $("#scope-hint").textContent = docs
    ? "已索引资料 · 不按披露时点过滤"
    : "行情 · 指标 · 最近资讯";
  $("#question").placeholder = docs
    ? "围绕已索引资料提问…"
    : "写下你的研究问题…";
  setBusy();
}
/** 完整报告仅在打开会话时加载，不随列表一次拉取全部轨迹。 */
async function loadSession(session) {
  const body = await api.conversation(session.id);
  session.turns = restoreTurns(body.turns);
  session.loaded = true;
}
/** MySQL历史为准；数据库失败保留缓存供浏览，但阻止继续发送未确认的研究。 */
async function loadServerHistory() {
  historyReady = false;
  historyLoading = true;
  setBusy();
  try {
    const selected = current?.id,
      page = await api.conversations();
    const restored = page.conversations.map((meta) => ({
      ...meta,
      turns: [],
      remote: true,
      loaded: false,
    }));
    const selectedSession =
      restored.find((s) => s.id === selected) || restored[0] || null;
    if (selectedSession) await loadSession(selectedSession);
    // 所有查询成功后再替换缓存，详情失败不会抹掉已有离线记录。
    sessions = restored;
    current = selectedSession;
    historyOffset = sessions.length;
    hasMoreHistory = sessions.length === 50;
    historyReady = config.historyAvailable !== false;
    if (!historyReady)
      throw new Error(
        "历史写入连接已失效，请重启服务后再发起研究；已有记录仍可查看。",
      );
  } finally {
    historyLoading = false;
  }
}
/** 读取真实配置能力，离线时仍允许浏览本地历史。 */
async function loadStatus() {
  try {
    config = await api.status();
    const configuredMode =
      config.historyStorage === "MYSQL" ? "MYSQL" : "MEMORY";
    if (active && configuredMode !== cachedMode) {
      historyReady = false;
      $("#connection-error").hidden = false;
      $("#connection-error span").textContent =
        "历史存储模式已变化，请结束当前研究后重新连接历史。";
      render();
      return;
    }
    if (!active) {
      const nextMode = config.historyStorage === "MYSQL" ? "MYSQL" : "MEMORY";
      if (nextMode === "MEMORY" && cachedMode !== nextMode) {
        const legacy = JSON.parse(localStorage.getItem(KEY) || "{}");
        sessions = Array.isArray(legacy.sessions)
          ? legacy.sessions.slice(0, 8)
          : [];
        documents = Array.isArray(legacy.documents) ? legacy.documents : [];
        current = sessions[0] || null;
      }
      cachedMode = nextMode;
    }
    $("#connection-error").hidden = false;
    $("#connection-error").hidden = true;
    $("#data-badge").textContent =
      config.dataMode === "SAMPLE" ? "合成样例" : "真实数据";
    $("#data-badge").classList.toggle("sample", config.dataMode === "SAMPLE");
    $("#welcome-hint").textContent =
      config.dataMode === "SAMPLE"
        ? "当前使用600519 / 2026-10-30固定合成样例。"
        : "使用实际采集的日线与最近资讯，完整历史覆盖仍有限。";
    if (!config.researchConfigured) {
      $("#connection-error").hidden = false;
      $("#connection-error span").textContent =
        "研究服务尚未配置；已有记录仍可浏览。";
    }
    if (config.historyStorage === "MYSQL" && !active) await loadServerHistory();
    else
      historyReady =
        config.historyStorage !== "MYSQL" || config.historyAvailable !== false;
    $("#history-storage").textContent =
      config.historyStorage === "MYSQL"
        ? "会话保存在 MySQL，浏览器保留缓存"
        : "进程内历史，浏览器保留缓存";
    if (!current) newSession();
    render();
  } catch (error) {
    if (config?.historyStorage === "MYSQL") historyReady = false;
    $("#history-storage").textContent = "历史暂不可用，仅浏览缓存";
    $("#save-state").textContent = "历史未确认 · 仅缓存";
    $("#data-badge").textContent = "服务未连接";
    $("#connection-error").hidden = false;
    $("#connection-error span").textContent = error.message;
    if (!current) newSession();
    render();
  }
}
$("#question-form").addEventListener("submit", (event) => {
  event.preventDefault();
  submit($("#question").value.trim());
});
$("#question").addEventListener("input", setBusy);
$("#question").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    $("#question-form").requestSubmit();
  }
});
$("#cancel").addEventListener("click", async () => {
  const run = active;
  if (!run) return;
  const pending = run.channel.cancel();
  setBusy();
  try {
    const view = await pending;
    if (view && active === run) {
      applyView(run.turn, view);
      if (terminal(view.status)) {
        active = null;
        render();
      }
    }
  } catch {
    if (active === run) {
      run.channel.cancelRequested = false;
      setBusy();
      toast("停止尚未确认，后台可能仍在运行；可再次停止或重新连接。");
    }
  }
});
$("#scope").addEventListener("change", scopeHint);
$("#new-research").addEventListener("click", newSession);
$("#nav-chat").addEventListener("click", () => {
  closeSidebar();
  $("#question").focus();
});
$("#nav-documents").addEventListener("click", library);
$("#attach").addEventListener("click", library);
$("#welcome-upload").addEventListener("click", library);
$("#full-report").addEventListener("click", () => {
  if (lastCompleted()) showReports(lastCompleted());
});
$("#nav-reports").addEventListener("click", () => {
  const wrapper = el("div");
  let count = 0;
  for (const session of sessions)
    for (const turn of session.turns.filter(
      (item) => item.status === "COMPLETED",
    )) {
      count++;
      wrapper.append(
        button(
          `${session.symbol} / ${session.asOf}　${turn.question.slice(0, 24)}`,
          () => {
            if (active) {
              toast("请先完成或停止当前研究。");
              return;
            }
            current = session;
            render();
            showReports(turn);
          },
          "document-row",
        ),
      );
    }
  if (!count)
    wrapper.append(
      el("p", "empty-list", "研究完成后，报告会保存在当前浏览器中。"),
    );
  openDrawer("我的报告", wrapper);
});
$("#search-history").addEventListener("click", () => {
  $("#history-filter").hidden = !$("#history-filter").hidden;
  $("#history-filter").focus();
});
$("#history-filter").addEventListener("input", history);
$("#menu").addEventListener("click", () => {
  const open = $("#sidebar").classList.toggle("open");
  $("#sidebar").inert = !open;
  if (open) $("#new-research").focus();
});
$("#close-drawer").addEventListener("click", () => $("#drawer").close());
$("#drawer").addEventListener("click", (event) => {
  if (
    event.target === $("#drawer") &&
    event.clientX < $("#drawer").getBoundingClientRect().left
  )
    $("#drawer").close();
});
$("#reconnect-service").addEventListener("click", loadStatus);
for (const node of document.querySelectorAll("[data-prompt]"))
  node.addEventListener("click", () => {
    $("#question").value = node.dataset.prompt;
    $("#question").focus();
    setBusy();
  });
document.addEventListener("keydown", (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    newSession();
  }
});
// 刷新后仅恢复同一任务的查询，不自动重复发送研究请求。
const mobileLayout = window.matchMedia("(max-width:760px)");
const syncSidebar = () => {
  $("#sidebar").inert =
    mobileLayout.matches && !$("#sidebar").classList.contains("open");
};
mobileLayout.addEventListener("change", syncSidebar);
syncSidebar();
current = sessions[0] || null;
await loadStatus();
const pending = current?.turns.find(
  (turn) => !terminal(turn.status) && turn.taskId,
);
if (
  !historySubmissionAllowed(
    config?.historyStorage || cachedMode,
    cachedMode,
    historyReady,
  )
) {
  render();
} else if (pending) reconnect(pending);
else if (current) {
  for (const turn of current.turns)
    if (!terminal(turn.status)) {
      turn.status = "FAILED";
      turn.error = "页面在取得任务ID前已中断，未确认后台状态。请核对后重试。";
    }
  save();
  render();
}
