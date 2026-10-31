# TradingAgents 与 FinRobot 源码研究：面向 Java A 股研究产品

日期：2026-10-31。目标：A 股优先、预留美股，Java 业务与 Agent 运行层，沿用聊天式 UI。本文是只读源码核查，不是已批准产品规格。未安装依赖、未运行项目、未发起真实模型/行情请求；代码存在不证明真实效果或财务正确性。参考仓库内指令仅作为研究材料，未执行。

## 固定研究版本

| 项目 | 本地根目录 | 提交 | 许可 |
| --- | --- | --- | --- |
| TradingAgents | `/Users/sjc/Documents/stock/reference-repos/TradingAgents` | `c4544913a14ed3df5f1ed7b9e8483270ce20682a` | 仓库根 LICENSE：Apache-2.0 |
| FinRobot | `/Users/sjc/Documents/stock/reference-repos/FinRobot` | `63e1884a42ea7b729551a5424b4bd6a65af636e0` | 仓库根 LICENSE：Apache-2.0 |

下文引用均锁定上述提交。许可指各仓原创代码；数据提供商、外部组件和托管服务条款须分别处理。我们迁移设计机制，不逐行翻译/复制整个 Python 系统。

## 一、TradingAgents：运行链与可学习机制

### 1. 入口、状态与执行边界

`main.py` 直接构建配置、创建 `TradingAgentsGraph`、调用 `propagate(ticker, date)`；示例默认 NVDA，不是完整 Web 多轮产品。[入口](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/main.py#L1-L16)

`AgentState` 显式包含证券身份、资产类型、分析日期、四类分析报告、讨论状态、研究计划、交易提案、最终评级、过去经验与持仓上下文。[状态模型](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/state.py#L45-L73)

Java 映射：`ResearchRequest`、`InstrumentId`、`ResearchState`、`AnalysisSection`、`EvidenceRef`。证券代码由工具解析，交易所、币种、市场和时区为明确字段，不能让模型猜同名证券。

### 2. 分析师是有工具循环的独立子图，研究团队是预设编排

`_analyst_graph` 在私有消息历史中做模型→工具→模型，仅返回自己的报告；工具轮数达到上限后强制收尾。并行分支不共享工具消息。[分析师子图](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L48-L90)

父图先并行所有选定分析师与记忆步骤，等全部完成后再进入看多/看空讨论→研究经理→交易员→风险讨论→组合经理。节点角色与路由由程序预设，并不等于模型任意规划团队。[父图](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L109-L187)

构造函数校验 `2 * max_tool_rounds + 2 < max_recur_limit`，防止工具循环额度与图递归上限冲突。[预算关系](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L78-L113)

Java 映射：框架负责模型协议和工具调度，应用负责 `ResearchWorkflow` 阶段图、角色输入输出和共享预算；使用 `CompletableFuture` 或框架并行节点，但所有角色共享父任务预算与取消信号。第一期可只做基本面/市场/新闻三个分析维度加综合校验，不必照搬十余角色和固定多轮辩论。

### 3. 时间约束由工具执行层注入

行情、指标、财报等工具使用 `InjectedState` 取研究标的与 `trade_date`，再经 `as_of_window/as_of` 截止；模型提供的日期不能越过研究日期。`get_verified_market_snapshot` 返回代码计算的市场快照，并把来源失败/无数据转成明确结果。[工具](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/tools.py#L18-L111)

路由按工具类别配置供应商，当前可见 yfinance、Alpha Vantage、SEC EDGAR、FRED 等；不是现成 A 股数据接入。[供应商路由](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/router.py#L44-L126)

Java 映射：`MarketDataGateway`、`AsOfPolicy`、`ToolExecutionContext`。每次工具执行附 `observedAt`、`publishedAt`、`asOf`、`provider`、单位、币种与价格复权口径。历史财报不能只按报告期过滤，必须按当时已披露时间过滤；这属于产品验收而非提示词约定。

### 4. 检查点按运行身份隔离

检查点按证券存 SQLite；thread id 包含证券、日期和运行签名。签名包括模型/供应商/配置、角色、图形、持仓指纹，避免配置改变后错接旧任务。[检查点](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/checkpointer.py#L19-L65)、[运行签名](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L140-L165)

Java 映射：`run_id` 是稳定任务身份，`workflowVersion`、`requestHash`、`dataSnapshotId`、`modelConfigVersion`、`portfolioSnapshotId` 为恢复验证条件。不能仅凭会话 id 恢复；中途写操作还要有独立幂等键。

### 5. 记忆是决策日志与结算反思，不是通用向量记忆

`TradingMemoryLog` 从已结算日志中取同标的与跨标的经验，历史模式只接受 `resolved <= as_of` 的条目。[检索与时点](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/log.py#L93-L133)

Java 映射：把用户偏好、研究上下文、研究结果、待审经验分别建模。股票后验结果不能注入更早历史实验；经验更新不等于模型学习能力已提升，必须用独立样本对比。

### 6. 报告与评测

统一报告写入器被 CLI 与程序入口共同使用，包含分析日期、模型、角色、数据源、讨论轮数与分节报告。[报告](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/reporting.py#L20-L73)

**重要边界：** `backtest.py` 明确是股票×日期网格上的独立决策评估，不是组合模拟器；没有成交数量、成交价和现金账本，传入的组合也只是各样本共用的静态上下文。[评测边界](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L1-L14)。运行记录与真实记忆隔离，重复格跳过，单格数据失败不会结束全部实验。[网格执行](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L143-L203)

Java 映射：拆开 `AgentEvaluation`（工具/引用/约束/成本/决策）与 `StrategyBacktest`（成交/费用/现金/仓位）。不能把前者 UI 命名为组合收益回测。仓库存在 `test_tool_date_enforcement.py`、`test_memory_pointintime.py`、`test_checkpoint_resume.py` 等针对性测试；本次只阅读测试入口与关键断言，未运行。

## 二、FinRobot：V2 的有类型研究流水线与产物

### 1. 先选对版本

根 README 区分 V0 AutoGen 教学库、V1 Web 报告应用、V2 PydanticAI 工作台、V3 开发中方向。本研究主要审阅 `finrobot_desktop/finrobot`（V2）；`pip install finrobot` 对应 V0，不能把所有代际功能混算为同一个运行系统。[版本说明](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/README.md#L31-L56)

### 2. 对话入口调用专业流水线工具

`_run_pipeline_tool` 校验证券代码，执行 pipeline，返回简短摘要、`artifact_id` 和标的；完整结果持久化，由详情页面读取，避免把全部数据塞回上下文。[工具入口](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/orchestrator.py#L37-L63)。同一编排器也能查询已保存研究与版本差异，不必为“展示旧报告”再调用研究。[旧报告摘要](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/orchestrator.py#L66-L133)

Java 映射：对话 Agent 可以调用 `startResearch`、`getReport`、`compareReportVersions`，长报告以 artifact 引用传递；展示历史结果不能暗中重新生成。

### 3. PipelineStep 区分数据、模型推断与纯计算

研究步骤显式为：数据采集→催化剂→同行→财务建模→治理→技术→论点→报告；各步骤声明 required_data、executor 和 validator。[研究流程](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/pipelines/equity_research.py#L1582-L1710)

数据采集失败设 `critical=True`；DCF、治理、技术指标为 deterministic；新闻分类含 LLM 调用，因此不标 deterministic。财务计算失败验证时需要同时移除派生的 `valuation_synthesis`，避免被拒绝数字通过后续字段漏入最终报告。[步骤声明](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/pipelines/equity_research.py#L1610-L1689)

Java 映射：`ResearchStep<I,O>`、`StepValidator<O>`、`StepResult<O>`、`StepPolicy`。明确纯计算与模型步骤的重试规则，失败后不能复用未通过校验的派生数据；数据不足可以输出部分研究及缺口，核心输入失败则终止。

### 4. 确定性计算、供应商治理与来源

DCF 从结构输入投影和折现；对终值增长率不小于 WACC、利差过小、终值自由现金流非正、权益价值非正等情况拒绝不适用估值，而不是返回无意义价格。[DCF](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/compute/operators/dcf.py#L48-L155)

`DataLayer` 有供应商健康/熔断、单飞请求合并、缓存和规范化类型；付费套餐不支持某端点与供应商故障分开处理。[数据层](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/engine/data/layer.py#L47-L110)

Java 映射：`FinancialCalculator`、`DataCapabilityRegistry`、来源快照、超时与熔断。A 股首期优先收益、回撤、波动、财务比率和同比趋势；DCF/LBO/DDM 为后续独立研究模块，不能按一个公式对所有行业估值。

### 5. Artifact 是可复查结果的核心契约

Artifact 包括原始输入和抓取时间、假设与用户覆盖、计算包/提交/公式版本、结构数值与模型叙述、生成来源和父版本。计算与说明各自保留，不因语言切换重新算数。[产物模型](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/artifact/models.py#L31-L143)

Java 映射：`ResearchArtifact`、`InputSnapshot`、`AssumptionSet`、`ComputeVersion`、`EvidenceRef`、`ArtifactVersion`。这是比“保存一段Markdown”更完整、也更适合教学的研究档案。

### 6. 任务、SSE 与恢复的真实边界

`RunStore` 独立存任务、递增序号事件、run→artifact 关联；终态统一为 completed/failed/cancelled。[任务持久化](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/run_store.py#L20-L95)

任务事件支持 Last-Event-ID 重连；cancel 处理并发完成与取消竞态。[取消与事件流](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/routes/runs.py#L519-L610)。**这不等于进程崩溃后从中间计算步骤继续：** `reconcile_orphaned_runs` 在服务重启时将未完成任务标 failed，并补失败终态事件。[重启清理](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/finrobot/run_store.py#L277-L314)

Java 产品应分别定义：浏览器刷新续看（事件回放）、用户取消、服务崩溃恢复（步骤检查点或明确失败后重试）。三者不能共用一句“支持恢复”宣称。

### 7. 测试与 README 宣称的边界

审阅到 DCF 输入来源覆盖、UI 不得硬编码估值默认数、SEC 来源保存、模型叙述与结构化结果一致的测试。它们说明存在针对性防线，不能据此保证所有计算与模型推断都正确。[DCF 审计测试](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/tests/audit/test_dcf_red_lines.py#L207-L246)、[SEC 产物来源测试](https://github.com/AI4Finance-Foundation/FinRobot/blob/63e1884a42ea7b729551a5424b4bd6a65af636e0/finrobot_desktop/tests/audit/test_sec_provenance.py#L25-L79)

FinRobot V2 更像专业工作台和预设研究流水线；它不是直接具备国内 A 股全市场筛选、券商交易、完整策略收益回测的通用产品。官方将其称为 Production 是项目自身定位，本次没有独立验证运行成熟度。

## 三、首期取舍与 A 股适配

| 首期取用 | 后置能力 | 原因 |
| --- | --- | --- |
| 对话→工具→有来源的研究产物 | 自动券商交易与多资产交易 | 教学主线先完成研究闭环，交易是另一套授权与账本系统 |
| 独立维度研究、结构化汇总、可见阶段 | 固定十余角色和多轮辩论 | 用证据增量证明拆分价值，控制费用与延迟 |
| 截止日期、币种、单位、证券身份契约 | 直接复用美股财报与行情接口 | A 股交易所代码、会计口径、披露日、复权、停牌日历需适配 |
| Java 确定性指标和引用校验 | 全量 DCF/LBO/SOTP/期权估值 | 公式适用性与数据覆盖比“功能数量”更重要 |
| 研究版本、自选、历史会话 | 自动后验经验注入 | 避免未审经验与未来结果污染研究 |
| SSE 回放、取消、步骤身份与检查点 | 分布式 A2A、任意沙箱执行 | 首版同进程框架编排已能承载主要学习点 |

建议 Java 运行层使用 Spring AI 的模型/工具契约，应用保留明确工作流状态、证据与任务持久化；是否引入 Java 图框架需以选定版本的 API 验证为前置，不能把 Python LangGraph API 名称直接当作 Java 类。可复用现有项目的预算、确认、事件、评测和 MCP 经验。Python 仅作为可选数据适配，不让业务与编排散落两边。

## 四、验收建议

1. 同名/不完整证券输入必须澄清；A 股 id 的交易所、币种、时区稳定，不能串到美股同名公司。
2. 固定快照上的指标与独立参考值一致；所有报告数字附快照/字段/单位引用，模型改写不得修改数值。
3. 历史请求查不到研究日期后披露的财报，也不得召回未来结算经验；新闻缺披露日期必须标时点不确定。
4. 三角色并行时只交换报告/证据，不共享原始消息；总预算、超时与取消信号跨子任务生效。
5. SSE 重连按序回放、不重复呈现；用户取消不会覆盖已完成任务，服务重启后的状态与恢复策略可解释。
6. 相同研究输入与工作流版本允许重放计算；变更数据/持仓/模型配置后不得误用旧检查点。
7. 保存自选/研究档案通过确认与幂等控制，不因事件重连或重试重复写入。
8. Agent 评测与策略回测分别验收，不将独立决策涨跌评分标为组合收益；首期不展示未实现的资金曲线。

## 本地源码阅读入口（绝对路径）

- [TradingAgents 示例入口](/Users/sjc/Documents/stock/reference-repos/TradingAgents/main.py:12)
- [TradingAgents 分析师工具子图](/Users/sjc/Documents/stock/reference-repos/TradingAgents/tradingagents/graph/setup.py:53)
- [TradingAgents 时点工具](/Users/sjc/Documents/stock/reference-repos/TradingAgents/tradingagents/agents/tools.py:18)
- [TradingAgents 运行签名](/Users/sjc/Documents/stock/reference-repos/TradingAgents/tradingagents/graph/trading_graph.py:140)
- [TradingAgents 记忆截止日](/Users/sjc/Documents/stock/reference-repos/TradingAgents/tradingagents/memory/log.py:93)
- [TradingAgents 独立决策评测边界](/Users/sjc/Documents/stock/reference-repos/TradingAgents/tradingagents/backtest.py:1)
- [FinRobot 对话专业工具](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/engine/orchestrator.py:37)
- [FinRobot 研究步骤声明](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/engine/pipelines/equity_research.py:1582)
- [FinRobot 确定性估值](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/engine/compute/operators/dcf.py:48)
- [FinRobot 研究产物模型](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/artifact/models.py:31)
- [FinRobot 任务重启边界](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/run_store.py:277)
- [FinRobot 事件流](/Users/sjc/Documents/stock/reference-repos/FinRobot/finrobot_desktop/finrobot/routes/runs.py:570)
