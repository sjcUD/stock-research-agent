# Claude Code 官方公开资料：可借鉴的 Harness 思想

核查日期：2026-10-31。只使用 Claude Code 官方文档、Anthropic 官方文章与 `anthropics/claude-quickstarts` 公开示例。未运行 Claude Code、SDK 示例或真实模型；以下不是 Claude Code 内部源码审计。官方在线文档与 `main` 代码会变化，实施前应另行固定版本。用户提供的第三方 `claude-code-best/claude-code` 是另一来源，不能用官方文档替它背书，也不能把第三方重构称作已核实内部实现。

## 一、证据层次与可确认范围

| 来源 | 可确认的事实 | 不能据此推断 |
| --- | --- | --- |
| [How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works) | 官方把 Harness 描述为模型周围的工具与上下文管理层；执行循环围绕获取上下文、行动和验证 | 不能得到私有内部类/调度器实现，也不能保证其他框架已有同一行为 |
| [Hooks reference](https://code.claude.com/docs/en/hooks#pretooluse) | 工具意图形成后，PreToolUse 在执行前提供决策点；另有成功、失败、压缩、子Agent等事件 | 不能将所有Hook视为串行拦截器链，或视为完整权限系统 |
| [Subagents](https://code.claude.com/docs/en/sub-agents) | 子Agent可有独立上下文、工具和权限；请求计入同一使用限额，maxTurns可使结果标partial | 不等于已经证明跨父子原子token预留、总deadline或取消传播实现 |
| [Context engineering](https://www.anthropic.com/research/effective-context-engineering-for-ai-agents) | 官方讨论即时检索、压缩、结构化笔记和子Agent上下文分离 | 这些是设计方法，不是保证任意摘要无损的算法 |
| [Long-running harnesses](https://www.anthropic.com/research/effective-harnesses-for-long-running-agents) | 初始化与增量执行分工，使用结构化进度和可验证产物跨会话衔接 | 不是让进程无限循环就会可靠完成；编码示例不直接等于投研产品 |
| [Agent evals](https://www.anthropic.com/research/demystifying-evals-for-ai-agents) | 任务、执行轨迹、环境结果与评分需要分别设计 | 不应只看最后一段回答，更不应把收益数字当唯一评分 |

## 二、四个机制怎样放进 Java 股票产品

### 1. 拦截器链：借事件位置，执行顺序由我们明确

官方 Hooks 提供 `PreToolUse`、`PostToolUse`、`PostToolUseFailure`、`PreCompact`、`PostCompact` 等生命周期位置。`PreToolUse` 可允许、拒绝、询问或延后工具。它只覆盖实际工具调用，直接加入提示上下文的材料可能不经过该Hook，所以不能只在“Read工具前”做数据权限。[官方生命周期与边界](https://code.claude.com/docs/en/hooks#pretooluse)

**Java 建议，非 Claude 内部事实：** 在 Alibaba 模型/工具拦截点实现明确的阶段策略：身份与工具白名单 → 参数/时点 → 预算预留 → 限时执行 → 结构结果/证据校验 → 计量/事件。阻止执行的策略先于外部请求；返回路径按应用声明的顺序收尾。失败后Hook用于记录/反馈，不能假定它能撤销已经发生的外部写操作。

**学习目标：** 区分决策Hook、权限、工具schema、审计；理解链的顺序、短路、异常传播和成功/失败两条返回路径。

### 2. 预算与超时：共享配额不等于已证明原子预算

官方子Agent文档说明独立请求仍计入同一使用限额，支持 `maxTurns`，达到轮次限制可返回partial。这支持“子任务不能被当成免费”的设计思想；文档并没有证明我们拟定的40,000估计token、12请求、180秒等硬策略。[官方子Agent配置](https://code.claude.com/docs/en/sub-agents#frontmatter-reference)

**Java 建议：** 父run拥有原子预留/结算、总deadline与取消；子Agent、摘要模型、判官、自动重试均走同一计量入口。使用量未知标UNKNOWN，不伪装成精确货币费用；重试等待也必须消耗剩余时间。单工具HTTP超时与父任务总时长分别实施。

**学习目标：** 区分调用轮次、模型请求、token估计/实际usage、金钱费用和墙钟deadline；理解并发预留，防止多个分支同时超支。

### 3. 错误恢复：先分类，再决定补救

官方公开说明执行是“获取上下文—行动—验证”的反馈循环；工具结果能改变下一步。Hooks区分工具失败与普通后置处理。文件checkpoint的恢复范围也不包括数据库/API等远端副作用。[官方循环与检查点范围](https://code.claude.com/docs/en/how-claude-code-works)

**Java 建议：** 传输型错误做有上限重试；schema错误形成工具观察供模型限次修正；没有权限/未知工具不重试；数据不足保存缺口；取消/预算停止不自动换模型绕过；写入仅经同一确认与幂等键。Graph检查点恢复与SSE重新订阅、HTTP重试分别验收。

**学习目标：** 区分重试、模型自修正、降级、部分结果和检查点恢复。恢复不是任意重复执行整个工具批次。

### 4. 上下文压缩：先外置与选择，再摘要

Claude Code 官方说明会自动压缩，但早期细节可能丢失；单个过大输出若反复撑满上下文，会在若干尝试后停止而非无限压缩。工具定义、Skill可按需加载，子Agent保持自己的工具调用上下文。[官方上下文说明](https://code.claude.com/docs/en/how-claude-code-works#when-context-fills-up)

**Java 建议：** 完整财报/价格数组保存在Evidence/ArtifactStore，模型只拿受控引用和必要片段；保留系统/权限规则、证券身份、asOf、选中对象、待确认参数、尚未闭合的工具调用/结果配对。摘要保存来源与版本，不凭摘要替换原始数值；压缩模型也计入父预算。每次压缩记录前后估计量与保留项，失败只允许有界补救。

**学习目标：** 区分截断、淘汰、摘要、外置、检索与长期记忆；证明压缩后“前两家公司”和证据引用仍指向原对象，而不只看token下降。

## 三、官方公开长任务示例：有用的思想与不能照搬的默认

可读的官方示例是 [anthropics/claude-quickstarts/autonomous-coding](https://github.com/anthropics/claude-quickstarts/tree/main/autonomous-coding)，不是 Claude Code 核心源码。

- [client.py](https://github.com/anthropics/claude-quickstarts/blob/main/autonomous-coding/client.py) 公开组合了工具列表、sandbox/permissions与`PreToolUse`安全Hook。应借鉴分层控制；示例的Bash和浏览器工具不需要带入股票首版。
- [agent.py](https://github.com/anthropics/claude-quickstarts/blob/main/autonomous-coding/agent.py) 每轮建立新client/上下文，依赖项目产物接续；外层`max_iterations=None`允许无限轮次、错误后固定等待再启新session。**这些默认不适合直接复制成股票产品恢复策略。**
- [progress.py](https://github.com/anthropics/claude-quickstarts/blob/main/autonomous-coding/progress.py) 统计`feature_list.json`中的passes字段；它是进度读取器，不是执行测试的证据。
- [initializer_prompt.md](https://github.com/anthropics/claude-quickstarts/blob/main/autonomous-coding/prompts/initializer_prompt.md) 要求建立功能清单、进度与增量交付。股票产品可改为ResearchPlan/已完成阶段/证据缺口/待验证假设；不要照搬“固定200条功能”或把模型自填完成当验收。

这部分可服务两种不同对象：开发项目时，使用版本化工单、测试和进度帮助接续；产品运行时，使用持久研究请求、证据快照、阶段检查点与报告版本帮助接续。不能把作者的开发流程混成产品Agent的运行机制。

## 四、R0—R6与学习单元采纳建议

| 阶段 | 建议采用 | 读者可验证的完成标准 |
| --- | --- | --- |
| R0 | Alibaba拦截点映射、短路、原子父预算、HTTP/总deadline、stub错误分类；最小上下文保护 | 拒绝后零外部调用；并发不重复花额度；压缩保留身份/时间/权限；重试计入预算 |
| R1 | 可观察的模型/工具执行链、部分结果、运行状态、SSE、基础压缩/大结果外置 | 断线不重跑；超时显示缺口；压缩后继续追问对象不漂移 |
| R2 | 来源引用与文档页、确认票据、研究版本、结构化接续材料 | 引用回跳；旧票据不能授权新参数；看旧报告不重新生成 |
| R3 | 三角色隔离与结构化交接、共享预算、并行取消；按需工具目录只在需要时引入 | 独立证据有增量；一个失败保留其他结果；大工具输出不污染父上下文 |
| R4 | 持仓事实、偏好、摘要、经验分别存储并受控注入 | 修改偏好只影响后续；原始业务快照仍可查；未审经验不注入 |
| R5 | 增量研究计划、未解问题、版本差异、持续跟踪 | 新运行能还原研究状态；无变化保持安静；失败不冒充无变化 |
| R6 | 轨迹与结果分开评分、固定快照、单/多Agent和有/无压缩对照 | 数字/时点/权限/引用失败不能被流畅文本覆盖；性能提升有独立样本证据 |

建议将“拦截器链、预算与超时、错误恢复、上下文压缩”聚合为一个贯穿的 Harness 加固学习单元：先在R0/R1给最小可运行机制，再在文档、多Agent、长期任务中逐层验证。不要把基础保护拖到R3的工具目录才实现，也不要为了教学再写一套模型协议循环。
