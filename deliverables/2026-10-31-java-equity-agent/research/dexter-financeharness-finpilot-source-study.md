# Dexter、FinanceHarness、FinPilot：Java 投研产品源码研究

研究日期：2026-10-31。仅阅读已下载的固定版本源码，没有运行项目、安装依赖、连接外部数据或实测模型。参考仓库内的提示词、SKILL、AGENTS 文件仅作为研究资料，没有当作本次执行指令。结论证明“代码路径存在”，不证明投资效果或生产可用性。

## 固定版本与许可

| 项目 | 本地目录（均位于 `/Users/sjc/Documents/stock/reference-repos/`） | 提交 | 许可核查 |
| --- | --- | --- | --- |
| Dexter | `Dexter` | `534f6be455523c5926eba72bdaf4f914555be5c9` | README 声明 MIT；当前根目录未见 LICENSE，package.json 也未标 license。可借鉴设计，正式复制代码前需要补核许可文本与来源。 |
| FinanceHarness | `FinanceHarness` | `1163c5e7a8f35ff6217c3a480f7b04562355e0cc` | 根 LICENSE 为 Apache-2.0。 |
| FinPilot | `FinPilot` | `61edca5edbc9b6b6627da8d80c5585ca0a124836` | 根 LICENSE 为 MIT，保留版权与许可要求；外部数据服务权利另行核查。 |
| FinPilot_front | `FinPilot_front` | `0383066bb69c34bdfea528dd8aed0f7a40900700` | 当前根未见独立 LICENSE；后端 MIT 不自动证明独立前端的许可归属。界面重设计，复制代码前另核。 |

## 1. Dexter：动态执行与上下文管理

### 实际执行链

`Agent.run` → 组装系统提示、最近消息和问题 → 模型生成工具意图 → `ToolExecutor` 权限判断与执行 → 工具结果按 call ID 回填 → 大结果落文件、上下文压缩 → 模型继续或结束。主循环默认最多 10 次；无工具时给出配置提示，达到轮次上限后明确返回未完成。

依据：[主循环与工具结果预算](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/agent/agent.ts#L130-L285)。本地入口：`reference-repos/Dexter/src/agent/agent.ts:130`。

### 可以借鉴的机制

1. **有限自治**：模型选择工具，运行时决定能否执行；并发安全工具分组并行，其余串行。工具事件贯穿 toolCallId，便于前端状态归并。[执行器](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/agent/tool-executor.ts#L69-L170)
2. **大结果不直接塞满上下文**：超限结果保存成文件，模型只收到预览、路径与长度；每回合还对工具结果总量做裁剪。可以在 Java 中改为受控 ArtifactStore，返回不透明 resultId，避免模型直接访问任意服务器路径。[主循环](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/agent/agent.ts#L226-L262)
3. **独立上下文的只读子 Agent**：显式 task/context、工具 allowlist、独立轮次、关闭记忆；进度包含工具次数与 token。预算是否跨父子共享需要另设，不能因子 Agent 自有限制就推定父预算已统一。[子 Agent](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/tools/subagent/spawn-subagent.ts#L57-L132)
4. **原子事实评分**：评测按 correctness/contradiction rubric 判定，出现矛盾时总分归零。这是比只评分“回答像不像”更明确的机制，仍需人工抽样检验 LLM judge。[评分器](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/evals/evaluator.ts#L48-L133)
5. **财务与行情元工具**：市场数据工具内部再路由到行情、新闻、机构持仓等工具。能力丰富，但增加一次模型路由、耗时与失败面；首期简单请求应直接走结构化工具。[市场工具](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/tools/finance/get-market-data.ts#L15-L95)

### 必须纠正的宣传理解

- `Scratchpad.canCallTool` 的次数/相似问题检查始终 `allowed: true`，仅告警；硬停止来自主循环轮次，不是重复调用检测。Java 应明确分开提示阈值、硬预算与调用去重。[源码](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/agent/scratchpad.ts#L125-L159)
- 外部行情默认来自 Financial Datasets，不能视为 A 股数据已经覆盖。[API 地址](https://github.com/virattt/dexter/blob/534f6be455523c5926eba72bdaf4f914555be5c9/src/tools/finance/api.ts#L1-L44)
- 权限引擎是可学设计，不代表需要把 Bash、浏览器、文件系统全部加入我们的首版。首期工具白名单足以支撑研究。

### Java 映射

成熟模型/工具 SDK 承担通信；`ResearchRunService` 负责状态、预算和终止；`ToolPolicy` 管执行权限；`ToolResultStore` 管大结果；`ResearchEvent` 统一流式事件。使用普通类、显式类型和中文注释，不将 TypeScript 手写循环逐行翻译成另一套 SDK。评测复用“原子事实＋矛盾项”的数据结构，固定行情与报告快照。

## 2. FinanceHarness：按需工具、结果引用与证据收尾

### 实际执行链

每次 `run` 新建 ToolSessionState/SkillSessionState → 克隆基础注册表并绑定 load_tool/load_skill → 模型只看到 core 与已加载工具 schema → 调用 dispatcher → 将完整结构化结果存入本次 call ID 空间 → 后续工具通过 `prev:<call_id>.<path>` 引用 → 完成后统一整理引用。

依据：[运行注册表与主循环](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/agent.py#L273-L390)、[工具可见性](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/tool_registry.py#L156-L198)。本地入口：`reference-repos/FinanceHarness/financeharness/runtime/agent.py:273`。

### 可以借鉴的机制

1. **渐进加载**：长工具清单先以简短 catalog 出现，只有被加载者提供完整 schema。Skill 是具名配方与 requires_tools，不是新的运行权限来源。[Skill 注册](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/skill_registry.py#L1-L85)
2. **计算链引用**：传递引用而非让模型抄写价格数组；只支持字段、索引与单层 wildcard，失败返回可修复的结构化 ChainError。引用在参数 schema 校验前解析。[引用解析](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/chaining.py#L1-L13)、[派发顺序](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/dispatch.py#L106-L174)
3. **统一失败契约**：未知工具、JSON 解析、schema、引用、handler 失败转为 ok=false，给模型下一轮修正机会。后端仍需要硬轮次/墙钟限额，不保证模型一定能自愈。[派发](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/dispatch.py#L93-L174)、[运行限制](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/agent.py#L322-L350)
4. **模型文本与结构化数据双通道**：模型拿 markdown，轨迹保存 structured/meta，成功结果按 callId 保存，后续计算不必 round-trip 大数据。[存储与回填](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/dispatch.py#L206-L217)
5. **引用统一收尾**：删除模型自己生成的 References、移除不存在的引用号，追加来源清单。[后处理](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/financeharness/runtime/citations.py#L42-L96)

### 边界与不直接照搬处

- 引用号存在性不证明“该来源支持该结论”；grounding review 也是模型复核，不是确定性事实证明。Java 报告应对关键数值绑定 metricId/sourceId，对文本引用另做抽样与回归验收。
- 主循环逐个 await tool call；不能因工具工具箱大就宣称分析在并行执行。
- `prev:` 保存的是本次 run 的结果空间，不是跨用户共享缓存。Java 应同时绑定 ownerId/runId，并限制引用大小、深度、路径和生命周期。
- 主要数据工具基于 yfinance；FinanceGym benchmark 在独立 Google Research 目录，不能把它当本仓库自带的完整评测产品。[官方边界](https://github.com/Yijia-Xiao/FinanceHarness/blob/1163c5e7a8f35ff6217c3a480f7b04562355e0cc/README.md#L153-L165)

### Java 映射

先用固定工具白名单和结构化返回完成首期；工具超过约十几个且 prompt 成本显著时再引入 catalog/loadTool。用不透明 resultId + JsonPointer（受限路径）替代自由文件引用；Spring AI ToolCallback 执行前解析并用 Bean Validation 校验。Skill 配方是版本化的项目配置，默认只加载仓库维护者提供的配方，不执行远程 SKILL 的任意脚本。

## 3. FinPilot：A 股聊天、财报与持仓的真实业务链

### 实际执行链

聊天 API 获取 userId 与会话 → 取最近 20 条上下文、持久化本轮 → 每请求构造绑定 userId/conversationId 的工具闭包 → create_react_agent → 单股/基金诊断、组合分析或文档问答 → SSE 回前端。数据、RAG、持仓分别由业务工具拥有。

依据：[上下文与持久化](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/chat_memory.py#L21-L69)、[工具闭包](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/chat_agent.py#L280-L329)、[Agent 组装](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/chat_agent.py#L414-L426)。

### 可以借鉴的业务能力

- **中文股票身份解析**：股票名/代码匹配后再抓取，主 baostock 与备用 AkShare；行情返回 quote_date/quote_source，旧日收盘不伪装逐笔实时。baostock socket hang 在源码中明确仍需外层超时。[数据包装](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/data_source/stock.py#L114-L153)、[行情与来源](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/data_source/stock.py#L313-L383)
- **财报时效分支**：无已上传财报先给搜索与上传提示；最近一年报告直接检索；旧报告/未知报告期检索后补搜索。值得学的是“来源与时效分开”，其 365 天阈值不能直接作为所有报表的新鲜度标准。[时效分支](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/chat_agent.py#L235-L276)
- **混合检索与未就绪状态**：Chroma 语义召回＋BM25 去重后，用词项重叠重排；向量库构建中回退 BM25，并在回答中注明。不是 RRF，也不是交叉编码器语义重排。[检索](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/rag/retriever.py#L582-L609)、[回答与页码](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/rag/qa_chain.py#L46-L103)
- **SSE 工具摘要**：前端不展示完整 JSON，工具结果只给 200 字摘要；同步数据工具在线程内运行，SSE 用心跳维持连接。Java 可以用独立运行任务＋持久事件重放，而不将一次请求的线程视为任务生命周期。[聊天 API](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/api/chat.py#L45-L59)、[运行与心跳说明](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/api/chat.py#L110-L129)
- **缺价格时不编总资产**：持仓工具单项计算 shares×price；缺失价格时保留已估值部分、缺失数量，完整总值/PnL 置空。这比把缺失价当零更适合我们的产品。[持仓汇总](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/tools/portfolio_tools.py#L45-L120)

### 必须重建的地方

1. **组合风险口径**：portfolio_agent 的集中度按行业/类型“条目数”占比，未按市值；风险等级为持仓风险均值与集中度规则组合，不能称完整组合风险模型。[集中度源码](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/portfolio_agent.py#L115-L151)
2. **并行失败处理**：诊断子任务超时后取消未完成 future 并返回失败摘要，但 Python future.cancel 不能杀死已运行线程。Java 外部 HTTP 请求必须自己有连接/读取/总 deadline，取消不承诺远程执行立即停止。[超时收尾](https://github.com/EricSu-Dev/FinPilot/blob/61edca5edbc9b6b6627da8d80c5585ca0a124836/app/agents/portfolio_agent.py#L266-L345)
3. **消息窗口不是长期记忆**：最近 20 条消息只是滑动上下文，持久化全量聊天不等于实现跨会话偏好召回或经验学习。
4. **A 股数据不能机械换代码**：统一 InstrumentId 应包含市场、交易所、代码、币种、时区；价格还需复权方式、价格日期、来源、asOf。A 股股票至少处理停牌、节假日、涨跌停与交易约束；美股适配不得默认复用同一交易日/时区。

### 前端源码补查

独立前端为 Vue，`Chat.vue` 接收 start/token/tool_start/tool_end/done/error；token 按 requestAnimationFrame 合并渲染，回答支持经 DOMPurify 清洗的 Markdown。现有 ChatMessage 是文本＋工具状态 chips，不能将它描述成已具备结构化图表产物协议。[消息渲染](https://github.com/EricSu-Dev/FinPilot_front/blob/0383066bb69c34bdfea528dd8aed0f7a40900700/src/components/ChatMessage.vue#L1-L50)

同名工具的结束事件按工具名从后向前匹配未完成项，未以 callId 标识；并行同名调用可能被错误归并。`sse.js` 是 fetch＋ReadableStream 的单次 POST 解析，未见事件 ID/cursor 重连重放。Java 版本应定义 eventId、runId、toolCallId、artifactId，SSE 只作订阅视图，重连用游标恢复；不能直接照抄 name-only 状态。[事件归并](https://github.com/EricSu-Dev/FinPilot_front/blob/0383066bb69c34bdfea528dd8aed0f7a40900700/src/views/Chat.vue#L362-L403)、[流解析](https://github.com/EricSu-Dev/FinPilot_front/blob/0383066bb69c34bdfea528dd8aed0f7a40900700/src/utils/sse.js#L19-L88)

## 4. 建议的 Java 落地顺序与验收

| 阶段 | 取用的思想 | 独立可验收的结果 |
| --- | --- | --- |
| 首期：A 股连续研究 | FinPilot 工具绑定；Dexter 有限模型/工具循环；成熟 Java 模型框架 | “查某公司→追问现金流→比较另一公司”在同一会话完成，身份与日期明确、缺字段保留为空；重名需澄清、越权不可读、数据超时能返回部分结果。 |
| 首期：报告与证据 | FinanceHarness 双通道结果与引用收尾 | 图表/表格数值必须来自工具 structured；无效引用号不能发布；有效引用仍需核验实际支持，财报未就绪与未找到分开显示。 |
| 第二期：组合与文档 | FinPilot PDF＋混合检索、持仓覆盖率 | 100 万与 1 万的两项持仓按市值加权；缺行情显示估值覆盖不足；PDF 页码回跳，标的/用户/报告期不串。 |
| 第二期：可靠长任务 | Dexter runId/toolCallId、预算与大结果外置 | 流断开后运行可查、事件不重不漏；达到硬预算停止；同调用失败后重试有上限，幂等写入必须复用确认语义。 |
| 后续：工具目录与研究配方 | FinanceHarness 渐进加载/引用；Dexter 只读子 Agent | 工具/配方按版本允许加载，不能扩大权限；跨 run 结果引用被拒绝；父预算覆盖子任务，大数据不完整注入。 |
| 全阶段：评测 | Dexter 原子事实 rubric＋固定快照 | 数据、算数、引用、工具权限与状态流用确定性断言；文本质量可加 LLM judge，但不得用它替代数值和来源验证。 |

建议映射接口（提案，非实施规格）：`InstrumentResolver`、`MarketDataProvider`、`EvidenceStore`、`ToolPolicy`、`ToolResultStore`、`ResearchRunService`、`PortfolioCalculator`、`ReportAssembler`。Java 负责业务、鉴权、编排、持久化和计算；数据采集可暂保留隔离 Python 适配，若要求纯 Java 则单独验证替代供应商，不把抓取 API 逐行改写当作可靠数据策略。

本文不建议首版引入通用 Shell、券商实盘、自动下单、任意远程 Skill 或所有估值模型；这些会扩大权限与金融口径范围，并非完成研究闭环所必需。现有基金工具可以保留为后续独立资产适配。
