# TradingAgents 深入研究：记忆、恢复、组合输入、历史数据与评测

日期：2026-10-31。仓库 `TauricResearch/TradingAgents`，固定提交 `c4544913a14ed3df5f1ed7b9e8483270ce20682a`。本报告为只读源码研究，未安装依赖、未执行应用或测试、未请求模型/行情服务、未读取 `.env`。代码仓库中的提示词和指令仅作研究数据。

## 1. 先区分四种“持久化”

| 对象 | 实际实现 | 不能混同的概念 |
|---|---|---|
| Graph 运行检查点 | 每个 ticker 的 SQLite + LangGraph SqliteSaver | 不是长期学习记忆，也不是聊天数据库 |
| 决策/反思记忆 | 本地 Markdown 文件，决策先 pending，未来窗口结算后写反思 | 不是模型训练，也没有在这条主路径采用向量检索 |
| 运行状态日志 | 以 ticker/date 写最终 JSON，包含报告、讨论历史、评级和配置摘要 | 不是每个网络请求完整可重放的数据集 |
| 阅读产物 | 各阶段 Markdown + 合并 Markdown/HTML | 不是交易成交单，也不证明每条论断已被独立校验 |

实现入口见[Graph 初始化和运行入口](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L73-L114)、[日志写入](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L438-L476)、[报告目录写入](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/reporting.py#L92-L136)。

## 2. Checkpoint / Resume 如何实现

调用链：`propagate` → `checkpoint_scope` → `begin_checkpoint` → 重新 compile(checkpointer=SqliteSaver) → 设置 `configurable.thread_id` → 若已有 checkpoint 则 invoke/stream(None)，否则传初始化 state → 成功记录决策并清除本轮 checkpoint → finally 关闭连接、恢复普通 graph。

关键实现：

- 数据库为 `<data_cache_dir>/checkpoints/<TICKER>.db`；thread ID 是 ticker+date+signature 的 SHA256 前16位。[存储与 thread_id](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/checkpointer.py#L19-L65)
- signature 包括分析师集合、辩论/风险深度、资产类型、portfolio 指纹、并行 graph 布局、绝大部分配置 hash；路径、是否启用 checkpoint、SDK retry 次数不改变签名。这能避免换模型、端点、语言、数据供应商后继续使用旧报告。[签名](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L140-L165)
- 恢复时必须传 `None`。重新传初始 state 会经消息 reducer 重复追加，项目已有显式修正。[恢复输入与生命周期](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L192-L253)
- API 与 CLI 都走同样的 begin/end 生命周期；CLI 中流式异常会跳过记录决策和清理，保留 checkpoint，下次可继续。[CLI 接续](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/cli/run.py#L216-L241)、[成功与 finally](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/cli/run.py#L344-L359)

边界：默认 `checkpoint_enabled=False`，需要开启；这是图节点状态恢复，不保证外部副作用 exactly-once，也没有证据支持它已经具备多租户 owner、任务租约和 fencing。单对象会重新 compile graph，不能据此认定一个 TradingAgentsGraph 实例可安全服务多个同时进行的 Web 请求。

可教学：状态机、reducer、执行状态与长期记忆的区别、恢复输入协议、配置指纹、清理资源与失败后保留证据。

## 3. Memory / Reflection 如何实现

不是“所有角色都自己学习”。本版本的主路径是：

```text
本轮最终决策
  → store_decision(ticker, trade_date, text, rating)
  → Markdown pending 记录
  → 后续运行的 Memory Log 节点与分析师并行启动
  → settle_all_pending 查询已经走完持有窗口的历史决策
  → 获取已完成收盘价，计算标的 raw return 与相对基准 alpha
  → quick 模型调用一次生成反思
  → 原子更新日志为 resolved，记录 resolution_date
  → 读取同标的最近 5 条决策/反思和跨标的最近 3 条经验
  → past_context 写入 state
  → Portfolio Manager 的提示词显式消费
```

证据及行为：

- `store_decision` 在文件锁内按 ticker+date 去重，不仅 pending 去重，已结算也挡重复；因此同一天换配置重跑，不会自动保存为两份可对比决策。[写入](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/log.py#L32-L72)
- 检索不是语义搜索：遍历已结算条目，按决策日期及写入位置排序，选同标的 5 条完整记录、跨标的 3 条反思。历史回测只取 `resolved <= as_of`，缺少 resolved 时间的老记录排除。[选择规则](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/log.py#L93-L133)
- 反思提示词要求2–4句，说明短窗口是否足以判断原命题；实际输出直接取 `llm.invoke(...).content`，这里未见对句数、因果有效性与经验可靠性的独立验证。[反思](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/reflection.py#L13-L63)
- 结算有单独文件锁，防两轮并发重复支付反思调用；每完成一条即写，失败条目仍 pending 并记录原因。[结算](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/settlement.py#L136-L196)
- 更新以临时文件+replace 完成，resolved 条目可配置轮转，pending 始终保留。[原子落盘](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/log.py#L137-L204)
- Memory Log 节点异常不终止当前研究：保留已存经验，状态里写 `memory_note`，报告显示未结算原因。[节点容错](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L309-L336)
- 实际消费点为 Portfolio Manager，不能把 Reflector 的文案“future analysts”理解成所有分析师都获得同一记忆。[注入点](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/managers/portfolio_manager.py#L26-L62)

这属于外部经验记忆+提示词接续，不修改权重，不证明反思能提高收益，也不应称强化学习。Java 版宜将决策、结果、经验候选分开存，记录 model/prompt/data 指纹；对低证据经验做审批或权重控制，而非直接变成事实。

## 4. Portfolio 是上下文输入，不是自动管理账户

Pydantic 定义 `Position(ticker, quantity, average_price)`、`PortfolioContext(cash, currency, positions)`，禁止 NaN/Inf；数量允许负数表示空头，股票符号匹配使用 normalize_symbol。将当前股票持仓、现金和其他持仓渲染成文本供决策层读取，fingerprint 纳入 checkpoint 签名。[模型与渲染](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/portfolio.py#L25-L81)

必须区别三种状态：

- `portfolio=None`：未提供账户信息，Graph 放空 portfolio_context，不假装用户空仓。
- `PortfolioContext(positions=[])`：明确提供空仓信息；现金若未填仍未知。
- 有 positions：按调用方提供的快照分析；代码不会自动查券商、校验余额或实际成交。

初始 state 的真实分支见[创建状态](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L293-L307)。`portfolio.py` 自述 broker-neutral，backtest 明确无数量/成交价/现金账本，因此 README 的“送模拟交易所执行”描述不能作为当前核心路径已实现模拟撮合的证据。

可教学：业务状态与提示词、未知与零的语义、快照指纹、分析层与个性化决策层分离。迁移 A 股时不能把“支持负数持仓”当普通账户可直接卖空，需要独立业务约束。

## 5. Point-in-time：历史运行如何防偷看未来

这不是只在提示词中要求模型“不要看未来”，而是在工具和供应商层拦截。

| 层次 | 实际机制 | 重要边界 |
|---|---|---|
| 工具参数 | `as_of` 将请求日期钳到 trade_date；`as_of_window` 规范化窗口，纯未来窗口整体向后平移 | 日期格式标准化影响字符串比较；无 run date 的工具直接调用没有同样上下文约束 |
| 新闻/社交 | 统一 UTC，保留日期窗口内项目；未标时间的项目只在接近现在时保留；覆盖不到窗口返回 unavailable | 最新帖子流不能还原完整历史，不可把抓不到当不存在 |
| 公司概况 | 历史运行拒绝 Yahoo/AV 的当前快照 | 历史估值、行业分类等不能从现在快照补写 |
| 财务报表 | SEC 取 filed<=as_of 的当时可见最新版本；Yahoo/AV 无披露日的历史报表 withheld | A 股非 SEC filers；仅改股票后缀不足以获得历史披露可见性 |
| 内部人交易 | 没有公告时间则历史运行 withheld | 交易发生日不等于市场知晓日 |
| FRED 宏观 | observation 结束日+realtime_start/realtime_end 均锁当时 vintage | 只限制经济指标观察期不够，修订值也会泄漏 |
| 经验记忆 | 只注入截至研究日期已结算的经验，跨标的同样过滤 | 已知未来结果的笔记也是未来数据 |

工具和窗口证据：[as_of / 日期窗口](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/date_window.py#L79-L111)、[时间与覆盖缺口](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/date_window.py#L24-L69)、[当前快照与无披露时间数据的拒绝](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/date_window.py#L114-L176)、[SEC filed 选择](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/sec_edgar.py#L154-L188)、[FRED vintage](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/fred.py#L192-L235)。

这里主要是按日研究，不是严格的分钟级公告/交易事件回放。即便数据层有日期保护，也不能自动消除基础模型预训练中可能掌握的历史结果；这需要评测设计承认限制。

## 6. Backtest 实际评分什么

`run_backtest` 对 ticker×date 网格逐个调用同一个 `propagate`。每次 sweep 有独立目录与 memory log，避免污染日常日志；已存在 ticker/date 的格子跳过，一个格子失败不终止整个 sweep，结束时再结算各 ticker 的最后一批 pending。[网格执行与隔离](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L143-L203)

结算口径很具体：

1. 研究日之后的**第一个收盘价**作为 entry，避免读取研究日收盘后新闻却假装当日收盘可成交。
2. 默认再经过5个交易日，以第5个后续交易日收盘为 exit；窗口没走完保持 pending，不拿部分窗口冒充完整结果。
3. 去除今天尚在交易的日K和非正价格；长节假日/停牌不足窗口时扩大查询范围。
4. raw=(exit-entry)/entry；benchmark 取两端日期当时最近的已完成收盘；alpha=raw-benchmark_return。按后缀选择地区基准，A 股 `.SS` 为上证、`.SZ` 为深证，配置可覆盖。
5. Buy/Overweight 的 alpha>0 记方向正确，Underweight/Sell 的 alpha<0 正确；Hold 不声明方向，不计算命中率；REVIEW 不计分。

收益计算证据：[已完成收盘与窗口](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/memory/settlement.py#L44-L125)、[评级统计](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L206-L236)。

必须收窄称谓：它是决策信号的方向/相对表现评测，不是完整策略回测引擎。源码开头明确无 quantity/fill price/cash ledger；传入 portfolio 时，每个格子看到同一静态账户，不滚动持仓。未在这些评分路径看到手续费、滑点、订单、换手成本、净值曲线、资金利用率或LLM费用扣减，不能把 mean alpha 当可实现账户收益。[范围声明](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L1-L15)

统计只使用日志中四舍五入到0.1个百分点的 alpha，单格只采样一次，文字源未完整归档，因此输出也明确说 indicative 而非可重复。[精度与限制](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L66-L76)、[汇总声明](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/backtest.py#L109-L127)。复用 run_id 时网格跳过依据是 ticker/date，而不是配置指纹；更换参数做消融应新建 sweep，避免旧格子混入。

Java 教学应另设 Agent 评测：证据支持率、数值一致性、工具选择正确率、失败恢复成功率、预算/延迟、压缩保真，再将方向表现作为独立维度。不能用一段上涨期的收益代替 Agent 正确性，也不能把简化信号评分宣传成可靠量化交易系统。

## 7. 模型分层、Provider 与重试

两档模型由 factory 分别创建：quick 用于分析师、研究员、风险辩手、Trader 和反思，deep 用于 Research/Portfolio Manager。每档可指定独立 provider、model 和 base_url；跨 provider 不复用主 provider 的地址。[创建与端点选择](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/llm_clients/factory.py#L137-L156)、[配置分层说明](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/default_config.py#L91-L137)

factory 针对 Anthropic/Google/Azure/Bedrock 原生 SDK 分支，其余走注册的 OpenAI-compatible 客户端。这里只说明适配代码存在，不能据此声称所有线上模型已经实测可用。[Provider 分发](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/llm_clients/factory.py#L7-L56)

`llm_max_retries` 只在明确配置时转为非负整数并传给 SDK，否则保留供应商默认；输出 max_tokens 可选并按 Google 键名转换。它们是单客户端参数，不是跨所有角色/反思/重试的父预算。[kwargs](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/llm_clients/factory.py#L94-L134)

CLI StatsCallbackHandler 用锁统计模型调用、工具调用和响应中的 usage metadata；缺失 usage 不增加 token。这是观测，未见在此拒绝超预算请求或原子预留的逻辑，也没有货币费用估算。[统计](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/cli/stats_handler.py#L9-L76)

因此 Java 版的 Harness 是必要补充：同一 ledger 覆盖 quick/deep/反思/重试/子任务；没有 usage 应标 UNKNOWN 而非误当零；取消和父 deadline 不能只依赖 SDK retry。源码有 max_tool_rounds 和 graph recursion_limit，但这些不能代替任务累计费用与时间限制。

## 8. CLI 过程展示与报告

CLI 收集 ticker、日期、分析师、深度、provider/model、语言等设置；无终端且缺必要选项会在调用模型前报出缺口。启动各分析师状态后消费 `stream_run`，逐条提取消息/工具调用，按 state 字段更新分析师、研究、Trader、风控和PM的报告与状态。[CLI setup](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/cli/run.py#L99-L139)、[流式消费](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/cli/run.py#L239-L340)

`stream_run` 使用 LangGraph `values/tasks` 和 subgraphs，分析师子图的任务结果完成即返回，上层状态等最慢分析师汇齐；不是浏览器聊天 API，也不是逐 token SSE 的完整实现。[流契约](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/trading_graph.py#L407-L436)

报告以 state 的字段生成五个层级：分析师、研究、Trader、风险、Portfolio Manager；头部保存日期、评级、版本、两档模型、轮数、供应商和 memory_note；只写有内容的章节。[报告组织](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/reporting.py#L20-L89)

Java 聊天版可以复用这套阶段观念，但需独立实现 conversation/run/attempt/eventId、SSE 重连去重、产物版本、证据抽屉和后续追问范围。不能只把终端文本套在聊天气泡里就宣称实现多轮 Agent。

## 9. 已看到的测试，不等于已运行验证

- [checkpoint crash/resume](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tests/test_checkpoint_resume.py#L50-L76)：构造图在 trader 崩溃，恢复后断言最终 count，验证节点状态接续。
- [memory point-in-time](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tests/test_memory_pointintime.py#L27-L78)：未来结算、旧条目、跨ticker经验过滤。
- `test_backtest.py` 对失败格子继续、live log隔离、重复格子、空结果、Buy/Sell方向、Hold无命中率等做单元覆盖。
- `test_portfolio_context.py` 对缺失/空仓/持仓、决策层可见、研究层不见与指纹变化有覆盖。
- `test_statement_knowledge_time.py` 对历史Yahoo/AV报表直接withhold且不发请求有覆盖。
- `test_tier_providers.py`、`test_llm_max_retries.py` 验证配置被传给正确客户端，不是供应商线上调用验收。

本次仅阅读这些测试，未运行，不能据此宣称测试通过或线上数据正确。固定SHA链接与行号由本地文件核验，后续 Java 复现应使用同等故障情形再加本项目的预算、取消、审批和证据保真验收。
