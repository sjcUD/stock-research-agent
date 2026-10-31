# Java A 股研究助手 Implementation Plan（评审草案）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for Native execution, or superpowers:subagent-driven-development if explicitly selected. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 Java 成熟 Agent 框架实现 A 股优先的对话研究产品，逐期交付证据报告、组合观察和持续跟踪，支撑系统学习。

**Architecture:** 独立 Spring Boot 产品入口，Spring AI Alibaba ReactAgent负责模型与工具循环，Alibaba Graph负责研究状态、路由与组合；业务服务拥有任务、权限、预算、证据和产物。PostgreSQL持久化任务及事件，React读取结构化结果与SSE；Java HTTP数据主适配，可选Python采集服务。

**Tech Stack:** 用户已选Spring AI Alibaba Agent/Graph；R0候选基线为 JDK21 / Spring Boot3.5.8 / Spring AI Alibaba1.1.2.2 / Spring AI1.1.2 / PostgreSQL16+pgvector；React19 / TypeScript / Vite / ECharts。R0产出锁定依赖，不沿用默认分支“最新”文档当API保证。

**Spec:** [02-java-design.md](02-java-design.md)。本次用户要求功能、设计、计划和UI一起交付，因此本文件按同包设计草案编写；它不是已批准执行的工单。修改设计时同步修订计划；任务1开始前先审阅整包。

## Global Constraints

- 主框架已确定为Spring AI Alibaba Agent/Graph；不同时引入LangGraph4j，不用旧手写循环或另一套自建阶段引擎替代它。版本组合仍需R0验证，产品设计/计划其余内容尚未整体批准。

- A 股优先，预留美股；首期日线/收盘研究，不宣称逐笔实时数据。
- 源码、测试、文章示例不使用record、sealed interface、var、模式匹配、getFirst/getLast；运行JDK21，核心有中文责任/流程注释。
- 项目根目录为`/Users/sjc/Documents/stock/`；原基金仓库的`java/`、`fund-data-mcp/`、既有schema、历史tags和原前端保持不变；股票产品拟新增`equity-app/`、`research-ui/`、`eval/equity/`、`docs/equity/`，执行时在股票项目建立独立功能分支。
- 业务/Agent/计算默认Java；主provider使用HTTP候选且有能力/权限检测；Python sidecar为可选，不作为默认启动必需。
- 默认研究累计活跃执行180秒（人工等待不计、关联恢复不重置ledger）、单模型最多60秒、12次模型请求、20次工具调用、工具15秒、只读重试最多1次、估计总token40,000/单次输入12,000/输出2,048；父子原子共享，UNKNOWN使用量明确显示。
- 每用户最多1个活动研究run，全局2个，R3每run最多2个并行角色；租约30秒/心跳10秒；事件保留30天；确认15分钟过期；等待24小时中断。
- 文档20MB/500页，仅PDF/UTF8文本；CSV2MB/1000行；最多5个公司比较；首版不执行任意代码或券商订单。
- DEMO与LIVE数据隔离且可见；无真实数据权限不视为LIVE验收通过。图片/样例数字不能进入真实报告。

## Review Focus

1. 同名公司、同代码不同交易所、未来披露材料：必须先解析身份并按asOf限制；任务2/6测试。
2. 同名并发工具、SSE断线、重复POST：按runId/toolCallId/sequence归并，无重复执行；任务3/5/8测试。
3. 取消/完成竞态、租约过期/旧worker回写、JVM重启：只接受一个终态且无迟到产物；任务3/8测试。
4. 缺价格/行业、分母为零、不同报表单位、导入重复项：保留缺口，不算完整权重或伪造0；任务2/4/10测试。
5. 旧确认/跨用户证据/不一致检查点/历史未来记忆：不能串权限与时点；任务6/7/8/9测试。

## 分期与依赖

| 里程碑 | 任务 | 可独立运行的交付 | 进入下一期条件 |
| --- | --- | --- | --- |
| R0 兼容与数据 | 1、2 | 官方框架调用+证券身份+数据能力/固定快照 | 版本、拦截、取消/预算和至少一条数据链的验证记录明确 |
| R1 第一版研究闭环 | 3→H1→4→5 | 查公司→同业比较→追问→证据→保存基础产物，刷新续看/停止 | 核心端到端及故障场景通过；LIVE和DEMO验收分开 |
| R2 文档与档案 | 6、7 | 财报引用回跳、报告版本、自选与确认 | 引文/时点/owner/重复确认通过 |
| R3 多维研究 | 8、9 | 独立角色、受控筛选、结果引用和研究状态 | 共享预算、部分失败、单Agent对照成立 |
| R4 组合与偏好 | 10 | CSV预览确认、不可变持仓快照、组合体检、偏好编辑 | 市值口径、覆盖缺口、导入与owner隔离通过 |
| R5 持续跟踪 | 11 | 站内周期复盘/披露更新提醒 | 跨重启调度、去重、安静期与暂停通过 |
| R6 历史实验 | 12 | Agent评测先行；受限策略回测另行验收 | 时点数据和交易规则足够，不足则只交评测不宣称资金回测 |
| 全阶段 | 13 | 测试、可复现资料、源码导读与文章索引 | 每个已交付里程碑都有对应学习与验证材料 |

Harness任务H1依赖1/2/3，先于4/5；编号H1为增补任务，不重排原13项。R2/R3都依赖R1；R4依赖R2；R5依赖R3/R4；R6取用前述固定数据与证据。按表顺序执行以减少接口返工。没有源码运行与provider权限实测前，不承诺固定工期；每期以可运行验收结束，不按菜单数量结束。

## Task 1：验证 Java 框架与版本，锁定产品入口（R0）

**Agent 学习目标（M01）：** 解释模型→工具意图→校验→执行→观察的完整循环；通过stub和硬预算验证框架与应用边界。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`equity-app/pom.xml`、`runtime/FrameworkAgentConfiguration.java`、`runtime/FrameworkCompatibilityProbe.java`、`research/ResearchGraphFactory.java`、`runtime/GraphCheckpointStore.java`（Java路径均相对`equity-app/src/main/java/io/github/sjcud/researchagent/`）；测试`equity-app/src/test/java/io/github/sjcud/researchagent/runtime/FrameworkAgentCompatibilityTest.java`；记录`docs/equity/adr/0001-framework-baseline.md`。

**Interfaces:** `FrameworkCompatibilityProbe.run(ProbeRequest): ProbeResult`验证Alibaba ReactAgent、普通不可变Java类schema、工具执行与流式事件；`ResearchGraphFactory.build(WorkflowVersion): CompiledGraph`为应用适配接口，返回类型以R0核实的框架API为准；`GraphCheckpointStore`负责持久化适配；`RunBudget.tryReserve(ModelRequestCost): Reservation`原子预留并能结算/拒绝。

- [ ] 先写`frameworkOwnsToolLoop()`：stub模型先请求一个只读工具再给回答，断言工具只执行1次、结果按callId回填，没有调用旧ReActAgent。
- [ ] 写`budgetStopsBeforeNextModelAndTool()`：给2次模型预算，第3次请求前被拒；两并发分支不能同时花同一份额度；缺usage标UNKNOWN。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=FrameworkAgentCompatibilityTest test`，确认未实现时失败。
- [ ] 按v1.1.2.2源码装配`ReactAgent`、Graph与工具回调，模型/工具拦截点检测重试计费、超时、取消和未知工具；不再叠加另一套ToolCallAdvisor循环。
- [ ] 增加`graphRoutesAndJoins()`与`graphResumesAfterJvmRestart()`：验证歧义分支、两个并行输出不覆盖、确认中断可持久化、重启后从同版本检查点恢复，不能把进程内事件或SSE重连当恢复。
- [ ] 同命令验证通过，记录依赖树`mvn -f equity-app/pom.xml dependency:tree`与API依据，锁版本；不兼容则先修订ADR，不进入任务2。
- [ ] 提交本任务文件；提交说明`build: verify Java research agent framework baseline`。

## Task 2：证券身份、日历、数据能力与快照（R0）

**Agent 学习目标（M02）：** 掌握工具输入/输出契约、身份与时点约束；能解释缺数据与计算为何不交给模型猜测。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`instrument/{InstrumentId,InstrumentResolver,TradingCalendar}.java`、`marketdata/{MarketDataProvider,ProviderCapabilities,TushareHttpProvider,SnapshotStore,AsOfPolicy}.java`；迁移`db/migration/V001__identity_market.sql`；固定样例`eval/equity/fixtures/market-v1/`。

**Interfaces:** `InstrumentResolver.resolve(String,Market): Resolution`返回resolved/ambiguous/notFound；`MarketDataProvider.fetch(DataRequest,RunContext): ToolResult`；`AsOfPolicy.allow(EvidenceRecord,Instant): boolean`。统一InstrumentId/MetricValue/ToolResult按设计定义。

- [ ] 写`InstrumentResolverTest`：600519锁定SSE/CNY，多个名称候选返回AMBIGUOUS；同代码不同market不共用缓存。
- [ ] 写`MarketDataContractTest`：研究日10月30不能读10月31披露材料；单位万元转元仅一次；无价格/无权限分别UNAVAILABLE；所有数据带时间/单位/source/basis。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=InstrumentResolverTest,MarketDataContractTest test`确认红灯。
- [ ] 实现固定DEMO适配、Java HTTP能力检测、限频、连接/读取超时与快照；任何token不写日志。权限不足减少能力，不自动换来源隐去口径差异。
- [ ] 同命令通过；有实际配置时做一条LIVE链的只读契约验收并脱敏保存结果，无配置标LIVE未验收。
- [ ] 提交`feat: add A-share identity and versioned data snapshots`。

## Task 3：持久 run、事件、租约、取消与恢复（R1）

**Agent 学习目标（M04）：** 掌握Harness运行状态、事件、取消与检查点；区分刷新续看、停止、服务中断恢复。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`identity/{LocalSessionService,WorkspaceAccessPolicy}.java`、`runtime/{ResearchRunService,RunWorker,RunLeaseRepository,RunEventStore,RunController,RunBudget}.java`；迁移`V002__runs_events_checkpoints.sql`。

**Interfaces:** `start(ResearchRequest,OwnerId): RunHandle`；`readAfter(RunId,long): List<RunEvent>`；`cancel(RunId,OwnerId): RunSnapshot`；`resume(RunId,OwnerId): RunHandle`产生parentRunId关联的新run。入队与请求同事务。

- [ ] 写`RunLifecycleIntegrationTest`：重复幂等键返回同run；跨owner404；同会话第二活动研究409；sequence严格递增；断线不取消任务。
- [ ] 写`RunRecoveryIntegrationTest`：过期租约置INTERRUPTED；旧fencing token拒写；取消/完成竞态只一个终态；fingerprint变更不复用检查点；新run只复用已验证阶段。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=RunLifecycleIntegrationTest,RunRecoveryIntegrationTest test`，使用真实PostgreSQL测试容器验证锁和CAS，不用内存库代替。
- [ ] 实现DB队列/租约/阶段检查点、30天事件和SSE；过期游标410+快照地址；工具执行服从deadline，取消后迟到结果不发布。
- [ ] 上述测试通过，再做一次重启/刷新人工验证，记录浏览器续看与服务恢复差异。
- [ ] 提交`feat: persist research runs and replayable events`。

## Task H1：Harness加固与上下文保护（R1前置）

**Agent 学习目标（M14）：** 理解拦截链顺序/短路、原子父预算与deadline、分级错误恢复、压缩后的协议与事实保真。详见[Harness补充](07-harness-design-and-learning.md)。R0任务1先做最小验证，本任务把它变成单股研究、多Agent与长任务共用能力。

**Files:** 新增`runtime/harness/{HarnessPolicy,BudgetLedger,DeadlineController,FailureClassifier,RecoveryPolicy,ContextCompactor,PinnedResearchContext,ModelPolicyInterceptor,ToolPolicyInterceptor,ContextPreparationHook}.java`（同Java根包）；`RunBudget`委托单一ledger，不维护第二份额度。测试`runtime/harness/{InterceptorLifecycleTest,SharedBudgetTest,RecoveryPolicyTest,ContextCompactorTest}.java`；迁移在任务3任务表上增加ledger引用/累计时间/摘要版本。

**Interfaces:** `BudgetLedger.reserve(AttemptCost,RunContext): Reservation`、`settle(Reservation,Usage): void`；`FailureClassifier.classify(Throwable,AttemptContext): FailureDecision`；`ContextCompactor.prepare(ContextSnapshot,RunContext): PreparedContext`；具体Alibaba拦截方法以R0已核实版本为准，不新建Agent循环。

- [ ] 先写`InterceptorLifecycleTest`：正常/前置拒绝/执行异常/后置异常四路；进入作用域按逆序退出；保留原始错误；权限拒绝后外部调用为0。
- [ ] 写`SharedBudgetTest`：两个角色竞争最后1次请求只能1个获准；每重试/摘要计入12次；恢复继承已消耗额度；取消后的真实usage仍结算；UNKNOWN不伪装为0。
- [ ] 写`RecoveryPolicyTest`：429/可重试5xx只读最多1次重试；401/403不重试；未知工具/参数最多修正1轮；同错误重复不无限循环；停牌无数据不产生价格0；流式attempt不直接重复追加。
- [ ] 写`ContextCompactorTest`：达到9,600估计token触发、目标不高于8,400；保留最新4个完整用户轮次及工具组；摘要最多一次且计费；失败回退裁剪；标的/asOf/约束/确认/evidence不漂移；不可压缩的保护内容超过12,000时返回PARTIAL。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=InterceptorLifecycleTest,SharedBudgetTest,RecoveryPolicyTest,ContextCompactorTest test`确认未实现时红灯。
- [ ] 通过Alibaba Model/ToolInterceptor与消息Hook实现策略，摘要走同一ledger/deadline；请求上下文与审计原文分离；工具HTTP有连接/读取/总时限，不能依赖Future.cancel强杀。
- [ ] 同命令通过；增加忽略线程中断的工具故障用例验证外层等待按deadline结束、executor关闭不无限阻塞；Graph检查点记录策略与摘要版本，调用attempt事件可查。
- [ ] 运行固定追问“只比较前两家”的压缩前后对照，验证对象和引用一致、输入token下降但事实不变；保存实际观测，未运行不能填通过。
- [ ] 提交`feat: enforce shared harness policies and context integrity`。

## Task 4：单股、最多5家对比与确定性计算（R1）

**Agent 学习目标（M03）：** 掌握多轮上下文、实体选择与澄清；区分预设Workflow和模型有限自治，能跟踪连续追问。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`research/{ResearchRequest,ResearchState,ResearchWorkflow,ResearchGraphFactory,ConversationContext}.java`、`marketdata/FinancialCalculator.java`、`research/tools/{ResolveInstrumentTool,GetMarketSnapshotTool,GetFinancialsTool,CompareCompaniesTool}.java`；测试fixtures及`ResearchWorkflowTest.java`。

**Interfaces:** `ResearchWorkflow.execute(RunContext,ResearchRequest): ResearchOutcome`；`FinancialCalculator.calculate(PriceSeries,MetricPolicy): List<MetricValue>`；`ConversationContext.applySelection(SelectionPatch): ConversationContext`。

- [ ] 写`ResearchWorkflowTest`：查公司→“比较另一家”→“只保留前两家”保留正确实体/日期；超过5家先要求缩小范围；不能把缺新闻写为无负面新闻。
- [ ] 写`FinancialCalculatorTest`：价格100、120、90的最大回撤为0.25；零净利润的现金流比率为null；单位与复权basis不一致拒绝比较。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=ResearchWorkflowTest,FinancialCalculatorTest test`确认失败。
- [ ] 通过Alibaba Graph构建RESOLVE/COLLECT/ANALYZE/VERIFY/PUBLISH节点与条件边，`ResearchWorkflow`只作业务图调用入口；工具返回证据和结构数据；模型只生成说明与引用，研究工具为READ。
- [ ] 同命令通过；固定问题至少覆盖正常、歧义、无数据、旧数据、timeout五类，输出PARTIAL不能伪装SUCCEEDED。
- [ ] 提交`feat: complete evidence-backed company research`。

## Task 5：聊天 UI、产物协议与基础保存（R1）

**Agent 学习目标（M05）：** 理解结构化输出、证据产物与UI事件的职责；证明文字、图表数值和工具状态按各自契约生成。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 后端新增`artifact/{ArtifactService,ChartSpec,ResearchArtifact,ArtifactValidator}.java`、`evidence/{EvidenceStore,MetricRefValidator}.java`；迁移`V003__artifacts_evidence.sql`。前端新增`research-ui/src/features/{chat,runs,artifacts,evidence}/`，组件`ChatWorkspace.tsx`、`RunProgress.tsx`、`ComparisonTable.tsx`、`MetricChart.tsx`，协议`src/api/contracts.ts`。

**Interfaces:** `ArtifactService.publish(ArtifactDraft,RunContext): ArtifactVersion`；前端`reduceRunEvent(state,event): RunViewState`按eventId/toolCallId归并，`ChartSpec`只允许设计中的v1类型。

- [ ] 后端`ArtifactContractTest`：不存在的metric/evidence引用不得发布；文本数字不能替换结构值；失败验证移除派生产物。
- [ ] 前端`runEvents.test.ts`：两个同名工具不同callId不串；重放事件不重复；跨run旧token不追加；未知组件降级文本并标明不可展示。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=ArtifactContractTest test`和`npm --prefix research-ui test -- --run`确认红灯。
- [ ] 按图1实现白蓝聊天主界面、空态/澄清/运行/部分失败/停止/完成、表格勾选对比、图表与来源入口；后端自动存基础产物，仅显式“收藏/加入关注”属后续确认操作。
- [ ] 测试通过；`npm --prefix research-ui run build`与`npm --prefix research-ui run test:e2e -- research-chat.spec.ts`通过；检查390px移动视图、键盘焦点、减少动态效果；原静态页不替换。
- [ ] 提交`feat: add conversational research UI and typed artifacts`。

## Task 6：文档入库、检索与来源页回跳（R2）

**Agent 学习目标（M06）：** 掌握文档解析/切分、Embedding、混合检索与引用验证；证明命中不等于结论得到支持。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`document/{DocumentService,DocumentParser,DocumentChunker,DocumentRetriever,DocumentController}.java`、`evidence/ClaimEvidenceValidator.java`；迁移`V004__documents_vectors.sql`；前端`ReportEvidenceView.tsx`、`SourceDrawer.tsx`。

**Interfaces:** `ingest(Upload,DocumentMetadata,OwnerId): DocumentHandle`；`search(DocumentQuery,RunContext): List<DocumentHit>`；hit含documentId/page/text/score/publishedAt。PDF库按依赖验证锁定，不将扫描件无文本当成成功。

- [ ] 写`DocumentEvidenceIntegrationTest`：20MB/500页限制；同名不同owner不可读；未来披露拒绝；页码与文字位置可回到原PDF；检索未就绪与未命中不同状态。
- [ ] 写`ClaimEvidenceValidatorTest`：引用号存在但不支持关键数字时不能标事实；无证据回答明确不足。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=DocumentEvidenceIntegrationTest,ClaimEvidenceValidatorTest test`确认失败。
- [ ] 实现PDF/文本解析、pgvector+词法检索合并及证据定位，原文按受控对象id读取；新建图2报告/抽屉视图，不渲染任意模型HTML。
- [ ] 上述测试及`npm --prefix research-ui run test:e2e -- report-evidence.spec.ts`通过；扫描PDF明确提示OCR暂不支持。
- [ ] 提交`feat: ground research reports in document evidence`。

## Task 7：确认、自选、报告版本与差异（R2）

**Agent 学习目标（M07）：** 掌握人在回路、精确动作授权和幂等；区分研究档案版本与实际注入的记忆。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`identity/ActionProposalService.java`、`artifact/{ArtifactVersionService,ArtifactDiffService,ArtifactExportService}.java`、`research/WatchlistService.java`；迁移`V005__actions_watchlists_versions.sql`；前端`ActionConfirmation.tsx`、`ResearchArchive.tsx`。

**Interfaces:** `propose(ActionRequest,OwnerId): ActionProposal`；`decide(ActionId,Decision,payloadHash,OwnerId): ActionResult`；`diff(ArtifactId,Version,Version): ArtifactDiff`。固定载荷哈希与15分钟有效期。

- [ ] 写`ActionProposalIntegrationTest`：重复批准仅一次副作用；改参数409；过期410；跨owner404；批准不授权不同run或新payload；拒绝不会写自选。
- [ ] 写`ArtifactVersionTest`：查看/导出旧报告不调用模型；新数据生成新版本；差异区分数据变化与文字变化；导出保留同版本数值/来源且不包含脚本与密钥。
- [ ] 先运行`mvn -f equity-app/pom.xml -Dtest=ActionProposalIntegrationTest,ArtifactVersionTest test`确认失败。
- [ ] 实现事务CAS+消费幂等，并通过Graph中断/恢复适配连接确认结果，原始票据仍由业务服务校验；实现版本父链与收藏；`ArtifactExportService.exportHtml(ArtifactId,Version,OwnerId)`使用固定清洗模板，浏览器可打印PDF。模型只提出操作，用户显式点击也会显示待执行对象与确认。
- [ ] 相同测试和`npm --prefix research-ui run test:e2e -- confirmations.spec.ts`通过。
- [ ] 提交`feat: add governed watchlists and research versions`。

## Task 8：独立上下文的三维研究（R3）

**Agent 学习目标（M08）：** 掌握Alibaba Agent组合、Graph状态/并行/汇总、上下文/工具隔离、结构化交接和共享预算；以对照验证协作价值。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`research/roles/{RoleExecutor,RoleReport,RoleToolPolicy}.java`、`research/{ParallelResearchStage,ReportSynthesizer}.java`；前端扩展`RunProgress.tsx`。

**Interfaces:** `RoleExecutor.execute(RoleTask,RunContext): RoleReport`；`ParallelResearchStage.execute(List<RoleTask>,RunContext): StageResult`；最多2并行，角色只读白名单，共享任务1预算与任务3取消。

- [ ] 写`ParallelResearchTest`：基本面/市场/新闻独立消息；只交换报告/证据；一个角色超时输出PARTIAL；总12次请求含子任务和重试；取消后不启动下一角色。
- [ ] 写`ParallelRecoveryTest`：恢复时复用已完成角色，未完成只读角色重跑；新数据指纹变化则不复用；同名工具callId隔离。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=ParallelResearchTest,ParallelRecoveryTest test`确认红灯。
- [ ] 用Alibaba独立角色Agent与ParallelAgent/Graph并行节点实现隔离和聚合，按选定版本验证maxConcurrency=2；汇总校验冲突与缺口，不以投票数代替证据。
- [ ] 相同测试通过；固定10个问题做单角色/多角色对照，报告事实、引用、成本/耗时，不以更长输出判优。
- [ ] 提交`feat: add bounded parallel research roles`。

## Task 9：受控筛选、结果引用与上下文策略（R3）

**Agent 学习目标（M09）：** 复用H1基础压缩，掌握大结果引用、受控筛选与工具目录边界；理解工具目录/Skill不扩大权限，未交付者只作规划。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`research/tools/{ScreenStocksTool,ReadToolResultTool}.java`、`runtime/{ToolResultStore,ToolResultReference,ContextPolicy}.java`、`research/ScreeningCriteria.java`；测试`ToolReferenceIsolationTest.java`、`ScreeningCriteriaTest.java`。

**Interfaces:** `screen(ScreeningCriteria,SnapshotId): ScreeningResult`；`read(ResultId,JsonPointer,RunContext): StructuredValue`，只允许当前owner/run可用引用；结果超过上下文预览限额只返回引用与摘要。

- [ ] 写测试：只接受已声明字段/单位；未知字段要求澄清；禁用任意SQL；跨run/owner引用拒绝；深度>4或展开>100项拒绝；旧日期/未来经验不得注入。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=ToolReferenceIsolationTest,ScreeningCriteriaTest test`确认失败。
- [ ] 实现白名单条件树、固定数据池与明确覆盖范围、工具结果JSON引用；上下文压缩保留实体/日期/引用，不用摘要覆盖原始证据。
- [ ] 同命令通过，并验证工具列表固定时不引入动态catalog开销；只有实测prompt成本需要才另写目录加载规格。
- [ ] 提交`feat: add scoped screening and tool result references`。

## Task 10：组合导入、覆盖口径与偏好（R4）

**Agent 学习目标（M10）：** 区分业务事实、会话摘要、偏好、经验与上下文注入；用组合实例验证确定性计算与缺口表达。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`portfolio/{PortfolioImportService,PortfolioSnapshot,PortfolioCalculator,PortfolioController}.java`、`identity/UserPreferenceService.java`；迁移`V006__portfolios_preferences.sql`；前端`PortfolioWorkspace.tsx`、`ImportPreview.tsx`。

**Interfaces:** `preview(CsvUpload,OwnerId): ImportPreview`；保存通过任务7 ActionProposal；`analyze(PortfolioSnapshot,PriceSnapshot): PortfolioAnalysis`。组合数值不由模型计算。

- [ ] 写`PortfolioCalculatorTest`：100万与1万持仓，第一项权重为100/101；少一项价格时完整总值/完整权重为null，不填0；只有带basis导入估值时能展示估计覆盖率。
- [ ] 写`PortfolioImportIntegrationTest`：2MB/1000行；重复证券合并份额；成本/币种冲突先修正；预览不写快照、确认仅保存一次、不能跨owner使用。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=PortfolioCalculatorTest,PortfolioImportIntegrationTest test`确认红灯。
- [ ] 实现CSV/手输→校验→确认→不可变快照；未知行业保留；共同日期不足不算相关性；偏好显式编辑，经验默认不自动激活。
- [ ] 测试及`npm --prefix research-ui run test:e2e -- portfolio-import.spec.ts`通过，按图3实现但使用UI说明修正的估值标签。
- [ ] 提交`feat: add portfolio snapshots and evidence-aware analysis`。

## Task 11：持久跟踪、差异与站内提醒（R5）

**Agent 学习目标（M11）：** 掌握授权后跨时间执行、触发上下文、去重与变化通知；区分无变化、失败与模型持续思考。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`tracking/{TrackingScheduler,ExecutionLease,ChangeDetector,NotificationService}.java`；迁移`V007__tracking_notifications.sql`；前端`TrackingWorkspace.tsx`、`NotificationFeed.tsx`。

**Interfaces:** `claimDue(Instant,limit): List<ExecutionLease>`；`detect(PreviousSnapshot,CurrentSnapshot): ChangeSet`；`publishOnce(ExecutionKey,ChangeSet): NotificationId`。

- [ ] 写`TrackingSchedulerIntegrationTest`：两个worker只有一个claim；重启不丢下次时间；executionKey防重复；节假日按日历推进；暂停任务不启动新执行。
- [ ] 写`ChangeDetectorTest`：无新数据零通知；同一披露重复采集零通知；有变更才发布；provider失败不发“无变化”结论。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=TrackingSchedulerIntegrationTest,ChangeDetectorTest test`确认失败。
- [ ] 用DB调度和任务7确认创建周期/新披露跟踪；只站内通知；失败退避且显示缺口。
- [ ] 同命令与`npm --prefix research-ui run test:e2e -- tracking.spec.ts`通过，演示静默期、变更、暂停三状态。
- [ ] 提交`feat: add durable research tracking and notifications`。

## Task 12：历史评测与受限策略实验（R6）

**Agent 学习目标（M12）：** 掌握事实/工具/引用/约束评测、LLM judge边界与消融对照；区分Agent质量与策略收益。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** 新增`evaluation/{AgentEvaluationRunner,EvaluationCase,EvaluationReport}.java`，`eval/equity/cases/v1.json`；策略部分另立`docs/equity/specs/strategy-backtest.md`后新增`evaluation/strategy/`。

**Interfaces:** `evaluate(List<EvaluationCase>,RuntimeFactory): EvaluationReport`按事实/数字/引用/约束/成本评分。StrategyBacktest只有独立规格获准、时点数据可用后才定义成交/现金接口，不混入Agent评分。

- [ ] 写`AgentEvaluationTest`：未来披露/未来经验拒绝；数字错误不能被流畅文字抵消；矛盾事实单独记失败；多次固定快照重放结果可比较。
- [ ] 运行`mvn -f equity-app/pom.xml -Dtest=AgentEvaluationTest test`确认红灯，随后实现独立评测与导出，再通过同命令。
- [ ] 将已有Agent验收集升级为至少30个固定case，覆盖身份、缺数据、时点、引用、并行、恢复、确认七类；LLM judge可选且不覆盖确定性失败。
- [ ] 策略规格明确只用当时可得数据、买入持有/固定周期再平衡、费用/滑点/分红/停牌/涨跌停/基准；缺数据则限制样本，不宣称全市场收益。
- [ ] 策略交付须用人工可复算账本测试现金+持仓+费用守恒，并与独立参考核对；未完成前UI只显示Agent评测，隐藏策略资金曲线。
- [ ] 提交`feat: evaluate research agents with point-in-time fixtures`；策略按独立计划另行提交。

## Task 13：每期验收、读者入口与发布（全阶段）

**Agent 学习目标（M13）：** 以固定版本的完整案例串起职责、状态、工具与证据；每期交付先修、机制、源码、验证与边界。 详见[模块学习地图](05-agent-learning-map.md)。

**Files:** `docs/equity/stages/R*/README.md`、`docs/equity/learning-roadmap.md`、`docs/equity/source-map.md`、`docs/equity/acceptance/`；新产品构建说明与根README的新入口在R1完成后再修改。

**Interfaces:** 每期记录固定commit、运行命令、DEMO/LIVE区分、输入输出、失败例、源码入口、下一章；不把原P阶段完成状态混入R阶段。

- [ ] 每期功能测试通过后，运行相应Java集成测试、前端测试/构建和已变动流程E2E；不能只截图当运行验收。
- [ ] R1首次完整执行`mvn -f equity-app/pom.xml verify`、`npm --prefix research-ui test -- --run`、`npm --prefix research-ui run build`、`npm --prefix research-ui run test:e2e`；后续按变动范围验证。
- [ ] 若提取或修改旧共享代码，再运行原工程受影响测试；没有修改旧代码则不为文档重复跑原全套。
- [ ] 每期补该期真实读者材料：先决知识、因果流程、小段中文注释源码、关键取舍、可运行练习/验证；文章正文按另行确认的篇目撰写，不生成空壳占位文。
- [ ] 对照功能清单F01—F18，记录已实现/未实现/限制；通过后创建对应R版本标签，代码与文档一起评审。
- [ ] 发布部署、外发通知、购买数据或券商接入均不隐含在本次计划批准中；当前计划只要求本地可运行研究产品。

## 覆盖与执行说明

F01/02/03/04/07/09/18→任务1—5；F06→6；F10/11→5/7/10；F05/08/16→8/9；F12→10；F13→11；F14→12；F15→H1、所有测试与13；F17仅预留身份/provider扩展点。首期不遗漏鉴权、预算和事件可靠性。

本文件没有触发任何创建应用、迁移数据库、模型调用或代码提交。设计与计划评审后，建议延续用户此前Native方式：主实现逐任务完成、记录验证，在主要交付点再进行独立审查；执行方式若改变应明确记录。
