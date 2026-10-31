# DeepSeek Harness：股票 Java Agent 的源码借鉴清单

研究日期：2026-10-31。官方仓库：https://github.com/deepseek-ai/deepseek-harness 。
固定 HEAD：`ce7073d51254c26cd63b22a60835b8f948deb447`。下文链接全部固定该 SHA，不随 master 漂移。
只读检查源码；没有启动项目、安装依赖，也没有执行仓库中的 AGENTS/CLAUDE/Skill 指令。

## 结论与适用范围

值得借鉴的是 Harness 的运行契约：可组合拦截、取消传播、明确的失败恢复、持久事件与模型可见上下文分离；不需要将 Cordis 插件运行时移植到 Java。
采用 Spring AI Alibaba 后，应由它拥有 Agent/Graph 循环；应用自己的 Harness 在已核实的模型/工具扩展点落实预算、超时、策略和审计，不能再套第二个循环。
README 宣称 everything-is-a-plugin、Cordis 驱动，并明确标记 developer preview、兼容性可能破坏；这是项目自述，不是稳定性保证。[README L5–15](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/README.md#L5-L15)
根许可证为 MIT，复制源码须保留版权与许可；依赖及第三方内容需分别核对，不应把根 MIT 推及所有内容。[LICENSE](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/LICENSE#L1-L21)

## 1. 拦截链：生命周期、策略与隔离

源码定义四段：`pre-execute` 决策 → `execute` around wrapper → `post-execute` 规范化结果处理 → `result` 最终只读观察。
前置可 allow/deny/cancel/ask；没有审批服务时 ask 转拒绝；工具抛错仍进入后置；最终观察者失败被收敛。作用域监听器仅收到对应 Agent 的调用。
`execute` 包装器只能改 signal，不能换调用身份；注册表重新融合原始调用者取消信号，避免插件断开取消传播。
证据：[tools/events.ts L8–49](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/core/tools/src/events.ts#L8-L49)。

真正执行路径在等待审批后重新检查 guards，并在 dispatch 前重新检查取消。审批结果不能覆盖当前已撤销的权限。
证据：[tools/pipeline.ts L184–232](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/core/tools/src/pipeline.ts#L184-L232)。
插件生命周期不是“移除监听器就结束”：retry 插件注销监听、abort 自己的 lifetime，再等待活跃恢复任务 settle，避免卸载后的迟到回调继续写状态。
证据：[llm-retry/index.ts L240–255](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/llm/llm-retry/src/index.ts#L240-L255)。

Java 借鉴：冻结 callId/owner/runId/参数哈希；审批后校验 owner、run、payloadHash、期限与权限；finally 逆序清理；保留原始错误及后置错误；取消后结算实际 usage，但不发布成功结论。
这里的作用域隔离是事件/工具策略隔离，不是操作系统沙箱，也不能证明第三方插件不可能绕过共享进程权限。

## 2. 超时与预算：必须分开建模

timeout-policy 是协作式取消：仅在工具声明 timeoutMs 时创建派生 deadline，等待工具 promise 完成，再将自身超时转换为结构化 TOOL_TIMEOUT，finally 恢复上游 signal。
它不 race/遗弃工具 promise，工具不响应取消可能仍然卡住；这不是强制终止，也不是所有工具默认有超时。
证据：[timeout-policy/index.ts L1–4、L55–80](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/guard/timeout-policy/src/index.ts#L55-L80)。

Java 借鉴：工具 deadline=min(工具上限,run 剩余时间)；HTTP 连接/读取超时、取消令牌与任务状态同时落地；Future.cancel 或线程中断不等于外部请求已经停止。
预算应区分模型请求次数、工具次数、输入压力、实际 token usage、按价格换算费用、run deadline；重试和压缩摘要也消耗父预算。
`token-meter` 是 replay-aware 的请求/上下文计量与用量投影，已读文件不足以支持“具备原子全 run 硬费用预算”的结论。
证据：[token-meter/index.ts L1–4、L56–94](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/llm/token-meter/src/index.ts#L56-L94)。

尤其不能把子 Agent 的 maxTokens 当父预算：descriptor 明确说它只约束一次 activation，不保存；cold resume 不恢复旧预算，也不继承当前父预算，使用恢复路由默认值。
证据：[subagent/descriptor.ts L1–19](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/subagent/subagent/src/descriptor.ts#L1-L19)。
股票项目需自己的单一原子 BudgetLedger：预留→执行→按实际 usage 结算；并发分支争最后一次额度只能一个成功；恢复不重置已花额度；缺失 usage 标 UNKNOWN，不能填零。

## 3. 错误恢复：有分类、有进展才重试

normal 模式检查 retryableCodes 与 maxRetries；always 模式没有次数上限。Retry-After 过长时 normal 放弃本插件重试，always 可改走本地退避。
指数退避有上限和 jitter；等待可取消；先记 llm/retry 事件再等待，不能把进程内计数当完整恢复状态。
证据：[llm-retry/index.ts L59–95](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/llm/llm-retry/src/index.ts#L59-L95)、[L146–187](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/llm/llm-retry/src/index.ts#L146-L187)、[L190–237](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/llm/llm-retry/src/index.ts#L190-L237)。

context overflow 的恢复更值得借鉴：仅处理对应错误码、有次数上限；压缩后必须看到 surface.replaceGeneration 改变才重试。
即使摘要失败，只要先前工具裁剪确实改变了持久上下文，仍可能重试；完全无变化则保留原错误，不盲目再次请求。
证据：[compaction-basic/index.ts L213–256](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction-basic/src/index.ts#L213-L256)。

Java 借鉴：只读外部调用对 429/可重试5xx/网络短故障有限重试；401/403、未知证券、停牌、缺数据、口径冲突分别处理。
模型参数修正是新的受限修正轮次，不是网络重试；带副作用的确认操作必须业务幂等，不能通用自动重试。
每个 attempt 独立记录，不把半截流式文本重复追加进历史；恢复前再次检查剩余预算、取消、deadline 和幂等条件。

## 4. 上下文压缩：事实保留比摘要优美更重要

普通压力路径先计量，超过阈值后先做无模型的工具结果裁剪，再重新计量；仍过大才选安全区间摘要。
context overflow 路径也先裁剪再选择摘要区间。这是实现顺序，不只是 README 承诺。
证据：[compaction-basic/index.ts L292–355](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction-basic/src/index.ts#L292-L355)。
工具结果裁剪保留头尾文本与富块次序；通过新事件替换模型可见 surface，记录被替换 sourceEventSeqs，原始已提交日志不被覆盖。
证据：[pruner/index.ts L76–121](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction-tool-result-pruner/src/index.ts#L76-L121)、[L124–175](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction-tool-result-pruner/src/index.ts#L124-L175)。

tool-pairing 通过待响应工具调用计数找平衡边界；不能切出孤立 tool result，也不能把尚未配对的调用切开。
证据：[tool-pairing.ts L28–68](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction/src/tool-pairing.ts#L28-L68)、[L104–125](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction/src/tool-pairing.ts#L104-L125)。

Java 借鉴：原始公告/财务表/工具响应进证据仓；模型收到 preview+resultId，按允许的字段分页读取；不是把金融表格简单截头尾后当完整事实。
不可压缩保护区包含 InstrumentId、asOf、币种/单位/basis、用户约束、确认票据、关键结论 evidenceId、缺数据与冲突标记；数值以结构化事实为准。
压缩快照记录覆盖区间、版本、输入摘要哈希、保留事实与来源；失败则安全裁剪或 PARTIAL，保护内容超容量不继续硬塞。

## 5. 会话、检查点、归档与子 Agent

SessionHandle 契约区分 append 可读/有序与 crash durability：只有 flush 成功才承诺崩溃后保存；读仅返回合法连续前缀。
证据：[session-persistence/handle.ts L73–109](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/session/session-persistence/src/handle.ts#L73-L109)。
Java 可以用数据库事务提交对应持久边界，先提交再发布关键 SSE；Graph checkpoint、业务 artifact、预算 ledger 必须有一致版本与恢复规则。
源码中的 compact-checkpoint 是上下文压缩标记，带 compactionId；不能据此声称它就是任意 Graph 节点的执行恢复检查点。
证据：[compaction/checkpoint.ts L19–50](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/compaction/compaction/src/checkpoint.ts#L19-L50)。
本次确认原始日志与模型可见投影分离；未核实独立冷归档、归档检索权限/保留期的完整实现，不能把 surface 替换称为已完成的归档系统。

子 Agent descriptor 是不进入模型历史的版本化持久事件，压缩不会删除它；显式快照组合字段，避免把任意扩展 options 当恢复协议。
证据：[subagent/descriptor.ts L29–48](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/subagent/subagent/src/descriptor.ts#L29-L48)。
委派深度以持久 header 的 delegationDepth 为不可降低底线，运行选项只能提高，防止恢复后深度归零。
证据：[subagent/depth.ts L18–35](https://github.com/deepseek-ai/deepseek-harness/blob/ce7073d51254c26cd63b22a60835b8f948deb447/packages/subagent/subagent/src/depth.ts#L18-L35)。
Java 角色可独立上下文与只读工具白名单，只交换结构化报告/证据；父 run 共享预算、取消、deadline；恢复时检查 workflow/model/data/prompt 指纹，复用已验证角色结果。

## R0–R6 如何逐步借鉴

| 阶段 | 应纳入的内容 | 能学到的 Agent 知识与可验证结果 |
|---|---|---|
| R0 框架验证 | 生命周期契约、一次调用身份、原子预留、取消与超时探针；锁定 Alibaba 版本真实扩展点 | Agent 循环属于谁；两个分支抢最后额度只有一个获准；前置拒绝外部调用为0 |
| R1 单股/对比 | H1 作为前置共用 Harness；有限恢复、预算结算、工具组裁剪、事件与检查点 | 从能回答到可控运行；429可恢复，401不重试；摘要计费；重启不重置预算 |
| R2 文档与确认 | 证据原文与模型投影分离；保护 asOf/单位/引用；审批后再校验权限与载荷 | RAG 证据生命周期、Human-in-the-loop；压缩后引用不漂移；过期批准不执行 |
| R3 多 Agent/筛选 | 隔离上下文、角色只读白名单、共享父预算、委派深度、resultId 引用 | 多 Agent 不是多份无限额度；大结果渐进读取；一角色失败给 PARTIAL |
| R4 组合/偏好 | 结构化事实、显式偏好、会话摘要分别存储；拒绝把摘要升级为业务事实 | 短期上下文与长期记忆边界；持仓/金额以业务快照为准，记忆可追溯 |
| R5 跨时间跟踪 | 任务租约、去重与明确每次执行预算；周期等待不占活跃模型调用 | 长任务与常驻思考的区别；重启恢复调度，无变化零通知，失败不能写成无变化 |
| R6 评测 | 重放实际模型可见上下文；故障注入与压缩消融；记录成本/延迟/事实引用保留率 | 评测 Harness 可靠性与 Agent 质量；收益不能替代事实/约束正确性 |

## 不宜照搬与尚未证明的能力

- 不移植 Cordis 动态插件框架；先使用 Spring AI Alibaba + 普通 Java 策略/拦截适配接口。
- 不因参考编码 Agent 而开放任意 shell、文件写、插件下载或外部代码运行；股票产品工具应是领域能力白名单。
- 不复制 always 无限重试；不把协作 timeout 当强杀；不把 maxTokens/token-meter 当全任务硬费用上限。
- 不把 scope 监听隔离当安全沙箱；审批、权限、凭据和副作用必须在业务调用边界校验。
- 不复制整套事件/JSONL格式就宣称实现了持久恢复；数据库、版本、事务、幂等、租约、fencing 都要以本项目验收为准。
- 上文是固定提交的源码观察与 Java 设计推导，尚未运行 DeepSeek Harness，也不宣称本文建议已在股票产品实现。
