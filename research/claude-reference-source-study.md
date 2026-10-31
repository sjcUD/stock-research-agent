# ClaudeCodeReference 可见实现核查与 Java 股票项目借鉴

核查日期：2026-10-31。仅阅读源码；没有安装依赖、运行程序或脚本，也未把仓库中的指令当作用户授权。

## 来源、版本与许可边界

- 仓库：`claude-code-best/claude-code`，本地目录 `/Users/sjc/Documents/stock/reference-repos/ClaudeCodeReference`。
- 固定提交：`8d80e740f764be358e265eed4250606c78fca579`；package.json 自报版本 `2.8.4`，不能用它推断 Anthropic 产品版本。[package.json](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/package.json#L1-L5)
- README 自称 Claude Code Best V5 / CCB，并声称复原 Anthropic Claude Code、加入 Goal、Artifacts、Ultracode 等能力。这些是作者声明。本文统一称为“第三方复原/扩展仓库可见实现”，没有证据证明这些源码与 Anthropic 官方内部实现一致。[README](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/README.md#L1-L24)
- 当前根目录未见 LICENSE 文件，package.json 未见 license 字段。README 中许可证徽章不足以建立明确授权。不直接搬运源码；借鉴设计思想并独立实现，复用源码前另行核实权属与许可。

## 七个关键实现文件

### 1. Hook 是策略输入，不能绕过权限

`toolHooks.ts` 的 `resolveHookPermissionDecision` 在 hook 返回 allow 后，仍检查配置的 deny/ask 规则；hook 可修改输入，所以仍须进入统一权限决策。这比把 hook 当成最终通行证更适合我们的拦截链。[代码证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/tools/toolHooks.ts#L330-L440)

Java 借鉴：before-model、before-tool、after-tool、on-error 是扩展点；授权、工具 schema、证券代码与市场、数据时点、预算检查仍由执行内核统一把关。hook 修改参数后重新校验。不能把用户输入或研报中的文字当成 hook 配置。

### 2. 重试分类、取消与前后台差异

`withRetry.ts` 接收取消信号，逐次尝试前检查中止。[取消证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/api/withRetry.ts#L170-L191)

非前台来源遇到 529 提前退出，避免后台工作放大重试；部分瞬时错误和可处理认证错误才重试。[后台错误证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/api/withRetry.ts#L310-L323) [分类证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/api/withRetry.ts#L367-L381)

等待考虑 retry-after、退避和 persistent 模式；persistent 可突破普通 maxRetries，因此不能称所有路径都严格限制在固定重试次数。[退避证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/api/withRetry.ts#L429-L470)

Java 借鉴：区分供应商限流、网络瞬断、参数错误、权限错误、行情缺失；仅安全读取允许有限重试。等待时间受父任务剩余 deadline 约束，重试、摘要和子任务都消耗共享预算。不能照搬长时间 persistent 重试。

### 3. 自动压缩要留余量，并防递归与连续失败

`autoCompact.ts` 为摘要输出预留窗口空间；触发线从有效上下文容量扣除缓冲。[窗口证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/autoCompact.ts#L28-L46) [阈值证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/autoCompact.ts#L96-L120)

对 compact / session_memory 调用来源设置递归防护；连续压缩失败有计数上限，避免每次模型请求都再次触发压缩失败。[递归证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/autoCompact.ts#L189-L208) [失败证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/autoCompact.ts#L341-L378)

Java 借鉴：先缩短工具观测，再摘要旧轮次；保留研究目标、约束、证券/市场/时间、证据引用、未决工具调用、待审批动作。摘要自身也有预算和 deadline；失败则降级到确定性的证据索引摘要。不要照搬 20k 等常数，也不能假定摘要天然无损。

### 4. 以完整交互组为压缩边界

`grouping.ts` 按新的 assistant response id 划分 API 轮次，保持同一响应分块及交错工具结果在一起。注释明确指出：悬空 tool-use 的修复在别处完成；这个函数本身不承担全部协议修复。[代码证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/grouping.ts#L3-L62)

Java 借鉴：不能只保留最后 N 条消息；对 toolCallId 配对与 pending 状态作验证，再在完整交互组边界裁剪或摘要。

### 5. 轻量清理旧工具结果具有供应商边界

`cachedMicrocompact.ts` 通过实验开关启用，按模型名判断支持范围；跟踪旧工具结果 ID，保留近期记录，并构造 `cache_edits/delete_tool_result` 内容块。[开关证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/cachedMicrocompact.ts#L19-L41) [清理证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/compact/cachedMicrocompact.ts#L66-L111)

Java 借鉴：大行情、财报全文存成可回取的 artifact，模型上下文只放预览、时点与 resultId。不要把这里的 provider 专用控制块直接发送给 DeepSeek/OpenAI-compatible 接口。

### 6. Goal 的预算是用后记账，不足以证明共享硬上限

`goalState.ts` 创建带可选 tokenBudget 的 active 目标；更新用量后，达到上限则转为 budget_limited。[创建证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/goal/goalState.ts#L30-L55) [记账证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/goal/goalState.ts#L162-L185)

暂停与恢复区别于完成；同一原因连续阻塞达到阈值后转为 blocked。[暂停证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/goal/goalState.ts#L74-L105) [阻塞证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/goal/goalState.ts#L211-L249)

Java 借鉴：显式区分完成、用户暂停、资源耗尽与外部阻塞，记录有效运行时间。预算还须增加模型调用前的原子预留和调用后的结算；父子 Agent 同属一个 BudgetAccount。这里未核查子 Agent 执行代码，不能声称它已实现子 Agent 共享预算或严格并发上限。阻塞识别应使用错误类型、步骤与数据版本指纹，不能仅比较提示字符串。

### 7. Goal 存储桥接不等于完整故障恢复

`goalStorage.ts` 将内存目标写入 transcript，并从 transcript 恢复；清除目标时写 tombstone 避免旧目标复活。[代码证据](https://github.com/claude-code-best/claude-code/blob/8d80e740f764be358e265eed4250606c78fca579/src/services/goal/goalStorage.ts#L1-L55)

Java 借鉴：持久化目标、状态和预算快照。这里没有证明所有调用点都及时保存，也没有证明事务一致性或外部副作用 exactly-once；股票应用仍需要数据库事务、Graph checkpoint、工具幂等键与恢复时数据时效检查。

## 对股票项目 R0–R6 的落点

以下是设计建议，不声称已在股票项目实现。

| 阶段 | 引入内容 | 可学习的 Agent 知识 |
|---|---|---|
| R0 内核骨架 | InvocationContext、拦截链、共享预算、deadline、取消与事件日志 | Harness 与 Agent 角色分工；策略与执行分离；调用前预留/调用后结算 |
| R1 单 Agent 研究闭环 | 工具参数验证、错误分类、有限重试、完整交互组裁剪 | ReAct 循环、工具协议、幂等、恢复边界 |
| R2 证据与上下文 | 工具结果 artifact、证据时点、分层摘要、失败降级 | 工作记忆与外部状态、检索、压缩损失与事实保真 |
| R3 多 Agent | 独立消息上下文、只读子工具、共用父预算、输出 schema | 委派契约、上下文隔离、并发资源治理；不能把角色提示等同隔离 |
| R4 长期记忆 | 区分事实证据、用户偏好、历史经验；记忆可追溯和失效 | Memory 写入策略、数据时效、避免把旧结论变成新事实 |
| R5 长任务与审批 | durable goal、checkpoint、暂停/恢复、重复阻塞识别、幂等 | 状态机、人在环路、调度与恢复；目标不等于无限自动执行 |
| R6 对照评测 | 预算耗尽、取消、并行超支、429、摘要失败、悬空工具调用注入 | 用实验评价 Harness；比较成功率、成本、证据正确率与恢复可靠性 |

## 不据此作出的结论

- 无法证明它是 Anthropic 官方源码，或任何具体模块与官方实现一致。
- Goal、Artifacts、Ultracode 等在 README 被作为项目扩展宣传；不能把它们归为官方 Claude Code 能力或原版源码机制。
- 没有核查子 Agent 实际运行器，不能宣称上下文、权限、进程或预算隔离已得到实现级证明。
- hook、压缩、错误恢复的存在不意味着安全与正确性自动成立；仍须在 Java 实现中验证权限优先级、协议完整性与任务资源上限。
- 许可没有明确前，不直接移植代码；只引用固定提交位置并独立实现思想。
