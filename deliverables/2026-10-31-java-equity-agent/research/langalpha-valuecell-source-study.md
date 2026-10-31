# LangAlpha 与 ValueCell 源码研究：面向 Java A 股投研产品

核查日期：2026-10-31。任务是读取下载源码、提炼功能和 Java 重建边界；未运行应用、未安装依赖、未连接模型/行情/券商。参考仓库中的 AGENTS、Skill、脚本说明均仅视为材料，没有作为本次执行指令。

## 1. 固定版本与研究路径

| 项目 | 本地目录 | 提交 |
| --- | --- | --- |
| LangAlpha | `/Users/sjc/Documents/stock/reference-repos/LangAlpha` | `d84f0c2595f7da0611b342d07b4b7442ee5fd66b` |
| ValueCell | `/Users/sjc/Documents/stock/reference-repos/ValueCell` | `9793e9c0563fbf56fc096757d8bb80e209ac7aab` |

以下链接均锁定这些提交。本地同名文件行号与链接一致。源码存在只证明实现结构可见，不能证明外部接口、金融结论或全部交互实际可用。

## 2. LangAlpha：值得借鉴的完整产品能力

### 2.1 工作区、对话、文件产物

工作区是持久研究对象，对话是其中的线程，run 是线程中的一次执行；沙箱并不等于工作区。构建图时按 conversation 取得 session，并要求 sandbox 和 MCP registry 已就绪。[图构建入口：`src/ptc_agent/agent/graph.py:158`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/ptc_agent/agent/graph.py#L158-L180)

文件路由使用明确的工作区 owner 检查，访问前获得沙箱，并在文件夹可移动场景下持有工作区行，避免路径移动后读到错误对象。[文件访问：`src/server/services/workspace_files.py:48`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/services/workspace_files.py#L48-L118)

Java 产品可保留“研究空间 → 会话 → 执行 → 报告/证据产物”结构，但首期不必给每个空间启动可执行代码沙箱。报告与图表先采用服务端生成的结构化产物和固定组件，持久存储需要独立于模型上下文。

### 2.2 后台执行与浏览器连接分离

`LocalRunExecutor` 以 `(thread_id, run_id)` 管理后台任务，SSE 读者只是订阅者；每次 POST 创建独立 run，线程 admission 再约束同一线程的并发进入。内部执行状态包含 running、completed、failed、cancelled、interrupted。[执行器：`src/server/services/runs/executor.py:1`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/services/runs/executor.py#L1-L108)

公开状态有独立映射：queued/running/stopping/recovering，completed/failed/cancelled，以及等待输入的 interrupted 与 idle。内部数据库拼写不会直接泄漏给前端。[状态契约：`src/server/contracts/status.py:1`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/contracts/status.py#L1-L88)

Java 映射：`ResearchRunService.startRun` 返回 runId；`RunWorker` 后台执行；`RunStateMachine` 管理显式转换；`RunProjection` 统一 UI 状态。断开 SSE 不等于取消，用户停止才持久记录取消意图。进程重启后依赖数据库与检查点恢复，不能仅靠 JVM Future。

### 2.3 可回放流与最终状态一致性

每个 run 的事件写入 Redis Stream，帧必须有可解析 event ID；事件数与字节数有配额，写入失败让执行显式失败，不以成功结束掩盖丢事件。[写端：`src/server/services/runs/stream_writer.py:64`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/services/runs/stream_writer.py#L64-L139)

读端 XREAD 按 cursor 读取，fresh attach 从头回放；已有 last_event_id 则只读后续事件，区分历史回放完成与实时尾部，并处理终结帧。[读端：`src/server/handlers/chat/run_stream_reader.py:109`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/handlers/chat/run_stream_reader.py#L109-L159)

Java 首期可用 PostgreSQL 事件表持久追加加 SSE，不必强制引入 Redis；先保证单调序号、run 内唯一约束、Last-Event-ID 回放、重复事件去重和状态/产物终结的一致性。把复杂传输结构直接照搬会扩大入门负担。

### 2.4 子 Agent 与成熟框架

子 Agent 编译时将 finance、web、filesystem、code 等工具分组，并用显式 roster 与配置裁剪；Task middleware 复制工具列表，不自动追加一个隐式通用角色。[子 Agent 装配：`src/ptc_agent/agent/subagents/assembly.py:31`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/ptc_agent/agent/subagents/assembly.py#L31-L122)

主 Agent 在框架 create_agent 上装配 middleware、checkpointer、store，然后外包后台子任务编排器；不是“一个提示词调几个角色”就结束。[主 Agent 装配：`src/ptc_agent/agent/agent.py:1169`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/ptc_agent/agent/agent.py#L1169-L1194)

Java 应借鉴接口边界：`ResearchRole`、`ToolPolicy`、`TaskResult`、父预算和专属上下文；首期同 JVM 内隔离上下文即可，不必先上 A2A 或独立 Agent 进程。角色产物需要结构化证据引用，不能只把几段自由文本拼接。

### 2.5 自动跟踪与确认

自动任务调度每 30 秒扫描数据库，使用原子 claim 并交给执行器，启动时处理遗留任务；这比浏览器定时器或每订阅一个永不结束的线程更适合持久跟踪。[调度：`src/server/services/automation_scheduler.py:1`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/services/automation_scheduler.py#L1-L75)

前端 inspector 可立即运行、暂停/恢复、编辑、看历史执行结果，且按“是什么、何时运行、最近发现、历史、设置”的顺序组织。[自动任务详情：`web/src/pages/Automations/components/AutomationInspector.tsx:41`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/web/src/pages/Automations/components/AutomationInspector.tsx#L41-L140)

交易权限有独立服务，不能由模型读到的文本提权；高自主等级需要当前版本 agreement，失效时回到逐次批准。HITL 回答区分逐个动作与按 order-attempt id 关联的裁决。[权限：`src/server/services/trading_permission.py:1`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/services/trading_permission.py#L1-L56)、[确认模型：`src/server/models/chat.py:70`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/src/server/models/chat.py#L70-L103)

我们的第一版可把同类机制用在保存偏好、导入持仓和创建订阅；实盘券商能力后置。批准绑定某次操作的参数哈希、用户、到期和幂等键，重试不能沿用旧输入的批准。

### 2.6 宣传边界、A 股与许可

下载版本 README 明确 hosted 行情是美股实时，A 股为 coming soon；self-host 是 Yahoo/FMP 每 60 秒刷新并标 delayed。A 股优先需要独立数据适配，不能因为产品叫金融助手就认定国内行情已经齐备。[版本能力表：`README.md:184`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/README.md#L184-L215)

README 对工作区同一 computer 共享 OS 用户也有明确解释；不能把“工作区隔离”说成操作系统安全隔离。动态 Python、Bash、生成应用、FUSE/对象存储、券商 relay 等是该产品的进阶系统负担，并非我们的首版前提。[工作区与 PTC：`README.md:225`](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/README.md#L225-L250)

仓库 Apache-2.0；本次未逐第三方包审计。托管服务、模型、云沙箱、行情商与券商 API 条件不随代码许可证一并授权。后端依赖有 LangGraph/deepagents，前端 React/Vite/Recharts 等图表组件，Java 重建应移植领域与契约而非逐行翻译。[后端依赖](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/pyproject.toml#L32-L105)、[前端依赖](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/web/package.json#L18-L67)、[LICENSE](https://github.com/ginlix-ai/LangAlpha/blob/d84f0c2595f7da0611b342d07b4b7442ee5fd66b/LICENSE)

## 3. ValueCell：轻量计划、消息与订阅组件

### 3.1 请求到编排的链路

路由 `/agents/stream` 将查询交给 AgentStreamService，输出 `data: JSON` SSE 帧；startup 调用 recurring tasks 恢复逻辑。[路由：`python/valuecell/server/api/routers/agent_stream.py:18`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/server/api/routers/agent_stream.py#L18-L58)

服务构造 UserInput 交给 orchestrator，但该路径 user_id 固定为 `default_user`。因此不能将此入口本身视为完善多租户认证/隔离实现。[服务：`python/valuecell/server/services/agent_stream_service.py:56`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/server/services/agent_stream_service.py#L56-L93)

编排分 conversation、plan、super agent、executor、event service。后台 producer 与局部 queue 解耦消费者，客户端离开时不取消执行；但暂停上下文保存在进程内 `_execution_contexts` 字典，不能据此宣称跨进程、跨重启恢复已完成。[编排与暂停上下文：`python/valuecell/core/coordinate/orchestrator.py:31`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/coordinate/orchestrator.py#L31-L146)

### 3.2 计划与人在回路

Planner 用 Agno，声明 PlannerResponse 输出 schema，工具可查询 Agent 描述与启用列表；模型会话 DB 为 InMemoryDb。UserInputRequest 基于 asyncio.Event 等待答复。这里的“等待输入”可借鉴，但 Java 方案必须把 pending request、原执行版本与答复写到持久层，避免进程重启丢失。[Planner：`python/valuecell/core/plan/planner.py:37`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/plan/planner.py#L37-L125)

### 3.3 事件、结构化组件与会话

ResponseRouter 把 A2A 的 failed/working、工具事件和 component_generator 转成统一响应；失败还返回副作用要求 orchestrator 将任务置为失败。[路由器：`python/valuecell/core/event/router.py:61`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/event/router.py#L61-L153)

ResponseBuffer 将消息片段合成有稳定 item_id 的条目，并对工具结果、组件、等待输入等事件立即持久化。这是聊天内图表/任务卡与历史回放保持关联的关键，不只是逐 token 输出。[缓冲器：`python/valuecell/core/event/buffer.py:79`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/event/buffer.py#L79-L170)

Conversation 仅存元信息，消息单独由 ItemStore 管理；状态为 active/inactive/require_user_input。[会话模型：`python/valuecell/core/conversation/models.py:8`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/conversation/models.py#L8-L60)

Java 应统一 `RunEvent`（runId、seq、type、itemId、payload）、`ChatItem` 和 `ArtifactSpec`。允许的图表/表格/引用/确认组件由固定前端渲染器选择；不要让模型生成任意可执行 HTML 进入主页面。ValueCell 的该 SSE 路由没有 `id:` 帧，按游标重播需要我们自行补足，不能仅依赖 EventSource 自动重连。

### 3.4 周期任务与新闻 Agent

Task 明确 pending/running/waiting_input/completed/failed/cancelled，pattern 区分 once 与 recurring，schedule 支持间隔或日时间；`cancel` 目前只变更本地状态并留有 remote cancel TODO。[任务模型：`python/valuecell/core/task/models.py:10`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/task/models.py#L10-L147)

TaskExecutor 对 scheduled task 先发控制组件，再循环执行，等待下一次 delay 并读取任务是否结束；重启逻辑只选择持久化的 RUNNING recurring tasks，重置 pending 后再起任务。[执行循环：`python/valuecell/core/task/executor.py:231`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/core/task/executor.py#L231-L315)、[重启逻辑：`python/valuecell/server/services/agent_stream_service.py:100`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/server/services/agent_stream_service.py#L100-L140)

NewsAgent 通过 Agno 选择 search/news 工具，将 RunContent、ToolCallStarted/Completed 映射流式响应。[新闻 Agent：`python/valuecell/agents/news_agent/core.py:27`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/agents/news_agent/core.py#L27-L78)

前端定时任务组件确有运行/取消状态与 Cancel API；Agent chat 对 StrategyAgent 使用专属页面，其他进入通用对话。[任务卡：`frontend/src/components/valuecell/renderer/scheduled-task-controller-renderer.tsx:9`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/frontend/src/components/valuecell/renderer/scheduled-task-controller-renderer.tsx#L9-L49)、[页面选择：`frontend/src/app/agent/chat.tsx:6`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/frontend/src/app/agent/chat.tsx#L6-L20)

Java 优先采用 durable schedule + execution ledger + 原子 claim；“任务卡显示取消”与“远程工具已停止”必须分开。订阅首先站内交付，失败与取消也有明确状态，外部消息渠道后置。

### 3.5 A 股和许可边界

AKShareAdapter 的确包含 A 股中文字段兼容映射和上交所/深交所/北交所识别，属于国内接入的可见实现；不能扩大解释为所有财报、公告、历史披露时点已覆盖或稳定。[A 股适配：`python/valuecell/adapters/assets/akshare_adapter.py:38`](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/valuecell/adapters/assets/akshare_adapter.py#L38-L113)

README 的 Roadmap 包含 Memory & Learning、WebSocket SDK 等，不能混同当前 SSE 与已见会话机制；远程 Agent 的 A2A 接入也不是 Java 首期实现研究团队的必要条件。原代码 Apache-2.0；TradingView 嵌入、交易所与模型 API 有各自条款。[README 路线图与许可](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/README.md#L186-L268)、[Python 依赖](https://github.com/ValueCell-ai/valuecell/blob/9793e9c0563fbf56fc096757d8bb80e209ac7aab/python/pyproject.toml#L10-L30)

## 4. 给 Java 方案的功能优先级和契约建议

### 首期应取

1. 聊天研究空间、会话历史、A 股名称/代码解析、明确数据日期与来源。
2. 单次研究后台 run、可回放 SSE、停止与失败状态，先 PostgreSQL 持久化，不强依赖 Redis。
3. 数据与文档工具的严格输入/输出 schema、模型调用预算、并行研究结果聚合与证据检查。
4. 报告/表格/图表产物有版本和证据关联，消息是引用产物而非唯一数据载体。
5. 保存自选、创建订阅、导入持仓前显示参数明确的确认卡，重试保持幂等。

### 进阶或后置

- 日历感知的周期/事件触发跟踪、研究版本差异、子任务进度与个人偏好。
- A2A 分布式 Agent、任意代码沙箱、动态工作流脚本、生成应用、FUSE/远程电脑、券商订单和加密资产交易。首版不以这些能力数量作为“完整”标准。

### 建议 Java 接口（本报告建议，不是既定实施规格）

| 接口 | 责任 |
| --- | --- |
| `ResearchRunService.start(RunRequest): RunHandle` | 校验 owner、幂等键、会话版本，创建持久 run 并返回标识 |
| `RunWorker.execute(RunId)` | 接受租约后执行框架/角色，检查预算和取消，保存 checkpoint |
| `RunEventStore.append(RunEvent)` / `readAfter(RunId, sequence)` | run 内单调事件、去重、回放；同一事件供前端与评测消费 |
| `EvidenceStore.save(EvidenceRecord): EvidenceId` | 保存 source、publishedAt、retrievedAt、asOf、instrument、值/单位、结果摘要或指纹 |
| `ArtifactService.publish(ArtifactDraft): ArtifactVersion` | 固定组件 schema，验证数值与 evidenceIds，不允许任意脚本 |
| `ConfirmationService.decide(ConfirmationId, Decision)` | user/参数哈希/有效期/CAS 审批，批准后单次消费 |
| `TrackingScheduler.claimDue(Instant): List<ExecutionLease>` | 以数据库原子 claim、防重复执行和交易日历处理持久订阅 |

### 推荐验收场景

- SSE 断开后 run 继续；从最后序号重连无缺帧/无重复 UI；超过保留期明确要求刷新快照。
- 单个研究角色失败时显示部分结果和缺口，不能伪装全流程完成。
- stop 与 tool completion 竞态时，持久终态、UI 和产物提交一致；后台取消请求不能被当成工具已取消。
- 创建订阅/保存自选重复提交或重试只产生一次副作用；旧参数批准不能授权新参数。
- JVM 重启时等待确认仍可见；运行中断后只从明确检查点恢复，无法恢复则显示 INTERRUPTED，而非回答前一轮。
- A 股简称歧义需要澄清；不同市场相同代码不串用；停牌/节假日/披露晚于研究日期时标记不可用。
- 同用户工作区隔离、不同用户所有权、任意路径访问、文件引用串空间均有独立验收。

这些场景也可直接转成教学章节：框架负责模型与工具循环，产品仍负责状态、证据、权限和持久性；能够从执行记录证明行为，才能让读者验证 Agent 的机制。
