# TradingAgents 四类分析师：从提示词到数据和报告的实现

研究日期：2026-10-31。固定提交：`c4544913a14ed3df5f1ed7b9e8483270ce20682a`。本文仅只读源码，没有安装依赖、调用行情/社交/模型 API，也没有执行仓库指令；“已实现”指该版本存在相应执行路径，不证明供应商当前可用、数据完整或投资有效。

## 1. 总体结论：四个角色不是同一种 Agent

三个角色——基本面、新闻、技术——是模型自主选择白名单工具的子图；情绪分析则是代码先取齐三类输入，再交给模型做一次归纳。四个角色都使用 quick-thinking 模型，管理者使用 deep-thinking 模型。[角色构建与模型分配](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L123-L146)

四个角色各自拥有私有消息历史，子图输出 schema 只暴露自己的 `*_report`；主图从 START 并行启动所选角色，等待全部及可选 Memory Log 完成，再进入 Bull Researcher。这是“并行取证、串行交接”的固定工作流，不是四个角色自由聊天。[子图隔离](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L53-L90) [并行汇合](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L157-L166)

| 角色 | 模型前数据如何进入 | 模型能选的工具 | 底层实际执行 | 最终状态 |
| --- | --- | --- | --- | --- |
| 基本面 | 证券身份、分析日期、私有 messages | get_fundamentals、get_balance_sheet、get_cashflow、get_income_statement、get_insider_transactions | 概况和内部交易默认 Yahoo；三表默认 SEC EDGAR→Yahoo | fundamentals_report 文本 |
| 情绪 | 程序预取最近7天新闻、StockTwits、Reddit，拼进 prompt | 无外部工具 | HTTP/RSS 获取、日期过滤、可选 Jev 相关性与立场分类，再一次 LLM 汇总 | sentiment_report 文本，由 schema 成功路径渲染或直接自由文本 |
| 新闻 | 证券身份、日期、私有 messages | get_news、get_global_news、get_macro_indicators、get_prediction_markets | Yahoo 新闻/宏观搜索、FRED 时间序列、Polymarket 概率 | news_report 文本 |
| 技术（market） | 证券身份、日期、私有 messages | get_stock_data、get_indicators、get_verified_market_snapshot | Yahoo OHLCV→stockstats 指标；可选 Alpha Vantage 指标 API；独立确定性快照 | market_report 文本 |

[工具到供应商映射](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/router.py#L97-L149) [默认供应商](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/default_config.py#L153-L170) [角色输出映射](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/analyst_execution.py#L20-L45)

## 2. 三个工具型分析师的通用循环

1. 工厂创建角色函数，构建带角色职责、工具名、trade_date、证券身份的 ChatPromptTemplate。
2. `take_turn` 将工具绑定给 LLM；有 tool_calls 时只写 messages，report 暂为空。
3. 子图路由到 LangGraph ToolNode 执行工具，将 ToolMessage 写回本角色消息历史；然后再次调用模型。
4. 模型本次不再发工具调用，则把 result.content 记为该角色 report，结束子图。
5. 默认 `max_tool_rounds=20`；统计的是含工具调用的 AI 消息轮数，不是实际工具调用次数。一轮可能并发调用多个工具。
6. 到达轮数上限时追加 WRAP_UP，人类消息要求根据已有结果收尾；最终这次不绑定工具，并把之前调用/响应转换为纯文本消息以兼容供应商协议。收尾节点之后无条件 END。

[工具调用与完成判定](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/analysts/turn.py#L13-L42) [有界子图](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/graph/setup.py#L48-L90) [默认轮数](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/default_config.py#L132-L143)

这比只在 prompt 写“不要无限循环”强，但仍不是本项目计划中的父级原子 token/费用预算。轮数不是额度账本，也不限制 sentiment 内部预取/Jev 额外请求。

## 3. 基本面分析师

### 3.1 角色本身做什么

prompt 要求综合公司概况、财务文档、财务历史，给有证据的观察并附 Markdown 汇总表。工厂只定义职责、工具集合、消息模板和 report 落点，没有在角色代码里实现 DCF、统一财务评分或会计报表自动审计。[角色源码](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/analysts/fundamentals_analyst.py#L13-L62)

实际链路：`fundamentals_analyst_node → take_turn → llm.bind_tools → ToolNode → agents.tools → route_to_vendor → vendor → 文本/CSV → LLM`。

### 3.2 取到的是哪些数据

- `get_fundamentals` 默认 Yahoo `Ticker.info`，取 PE、PB、EPS、收益率、ROE、负债权益、自由现金流等已有字段，标注报价币种、报表币种及部分百分比单位。它不是模型自己计算这些指标。概况是当前快照，历史日期会被拒绝提供，避免把今天的估值塞入过去分析。[Yahoo 概况](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/fundamentals.py#L23-L104)
- 三张报表优先 SEC EDGAR；依据 fact.filed<=as_of_date 选择当时已披露版本，季度/YTD 保留原披露跨度，而不是随意反推第四季；非 SEC 覆盖证券继续走配置中的 Yahoo。Yahoo 三表没有可信披露 vintage，历史运行会 withheld，不能把会计期间结束日当成公开可知日期。[SEC 按披露过滤](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/sec_edgar.py#L154-L225) [Yahoo 三表](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/fundamentals.py#L107-L145)
- 内部交易来自 Yahoo，历史运行也有 withholding guard。正常没有交易记录与网络取数失败分开处理。[内部交易](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/fundamentals.py#L148-L166)

### 3.3 能力边界

“识别内在价值和危险信号”主要由模型解读已有字段与报表完成。该角色没有强制逐结论 evidenceId、数值勾稽、固定估值公式，也不强制调用五个工具全部完成。Java 复现时应把确定性比率/估值计算留给可测试工具，再让模型解释。

## 4. 情绪分析师

### 4.1 不是工具自治，而是预取数据再分析

`sentiment_analyst_node` 从 state 取 ticker 和 end_date，算前7天 start_date，依次调用 `get_news.func`、StockTwits fetcher、Reddit fetcher。可选 `jev_screen(ticker)` 传入两种社交源。程序拼出三个来源块，之后一次结构化模型调用，没有 bind_tools。[预取路径](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/analysts/sentiment_analyst.py#L47-L125)

注意：get_news 真实走配置 vendor router；prompt 固定把新闻块叫 Yahoo Finance，若用户换成 Alpha Vantage，这一标签可能不再精确。三种抓取在节点中是顺序调用，四角色的并行不意味着这三种来源同时获取。节点注释说预取都能优雅降级，但 get_news.func 对核心新闻链路的未分类错误仍可能抛出，节点没有统一 try/catch；不能从注释推断“任何取数失败都会生成情绪报告”。源码中明确的 unavailable 哨兵与未知异常导致中断要分开。

### 4.2 StockTwits 是否真的抓取

是。HTTP 请求 `api.stocktwits.com/api/2/streams/symbol/{ticker}.json`，默认10秒 timeout，读取 messages，按 created_at 裁到分析窗口；正文 HTML 解码、限280字符；读取用户自标 Bullish/Bearish/未标，确定性统计条数与百分比。分母包含未标记帖子，不能误读成仅看多/看空之比。[请求与过滤](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/stocktwits.py#L74-L128) [计数与格式化](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/stocktwits.py#L130-L162)

它只读取近期 stream，没有任意历史日期归档。源码会提示 coverage gap/unavailable；“日期过滤不泄露未来帖子”与“拥有完整历史情绪数据”是两回事。

### 4.3 Reddit 是否真的抓取

是。实际使用公开 Atom/RSS 搜索，合并 `r/wallstreetbets+stocks+investing`，q=ticker、sort=new、t=week、limit=100；不使用已受阻的匿名 JSON API。解析标题、发布时间、正文片段及 subreddit；每社区默认最多显示5条，正文限240字符。[搜索参数](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/reddit.py#L68-L112) [RSS 执行与解析](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/reddit.py#L185-L245) [分组与片段](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/reddit.py#L248-L337)

RSS 没有点赞和评论计数，prompt 明确不允许推断 engagement。429 只退避重试一次；没有 Retry-After 时等待约60秒并加抖动，因此需要迁移到 Java 的父 deadline 管理，不能照搬无全局剩余时间约束的 sleep。取数失败返回 unavailable，不能写成“社区没有讨论”。

### 4.4 Jev 可选过滤是什么

只有配置 TYPESAFE_API_KEY 才启用。对每帖询问是否与证券相关、作者立场；相关性<0.3 才丢弃，立场 confidence<0.5 归 unclear；最多16线程并行，每帖是独立外部 API 请求，失败时保留未筛选原帖并注明失败。[阈值与问题](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/post_screen.py#L27-L61) [开启、筛选与并发](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/post_screen.py#L133-L181)

这不是 TradingAgents 训练出的情绪模型，也不是最终0–10分的数学计算式。

### 4.5 “单一情绪读数”怎样生成、保证什么

模型读取三来源，输出 overall_band、overall_score、confidence、narrative。Pydantic 硬约束方向枚举、分数0–10、confidence三档；方向和分数对应区间只是字段说明，没有跨字段 validator。confidence 是否真的与样本量一致、narrative 引用是否支持结论也没有在此硬校验。[SentimentReport](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/schemas.py#L296-L379)

结构化绑定不支持或调用失败时，会退到普通 llm.invoke 文本输出，所以不能宣称所有 provider 路径都有同样 schema 保证；最终存入 state 的仍是渲染后的 Markdown 字符串。[结构化与文本回退](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/structured.py#L42-L95)

新闻/社交原文直接拼进 system_message。这里分隔标签只表达来源，并不构成可信权限隔离。Java 方案应保留外部文本为数据的信任级别，权限与写入操作在工具边界执行，不靠“报告说了什么”授权。

## 5. 新闻分析师

### 5.1 四个工具的分工

| 工具 | 实际数据 | 实现方法与边界 |
| --- | --- | --- |
| get_news | 个股相关新闻 | Yahoo get_news，无响应时搜索 tagged ticker；取标题、供应商摘要、publisher、link；按发布时间过滤。没有通用网页正文抓取 |
| get_global_news | 宏观新闻 | 按配置的央行、通胀、GDP、地缘等搜索词调用 Yahoo Search，日期过滤、标题去重、条数限制；并非持续监控所有全球新闻 |
| get_macro_indicators | 宏观真实数值 | FRED series metadata + observations；别名映射到 CPI/失业率/利率等；传 realtime_start=realtime_end=asOf 锁历史 vintage |
| get_prediction_markets | 前瞻事件概率 | Polymarket 搜索开放事件，过滤活跃/未结束市场后按交易量排序，取 implied probability；历史日期直接 withheld，无历史 odds vintage |

[新闻角色工具与职责](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/analysts/news_analyst.py#L12-L61) [个股新闻抓取和过滤](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/news.py#L16-L134) [宏观搜索](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/news.py#L137-L201) [FRED 历史 vintage](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/fred.py#L167-L241) [Polymarket 历史拒绝与实时查询](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/polymarket.py#L86-L151)

宏观数字及变化率由程序取数计算，事件影响、关联因果和风险含义由模型归纳。新闻报告是文本，不是事件数据库；“监控”在这里指每次研究运行主动获取，不等于常驻任务和实时推送。

## 6. 技术分析师（Market Analyst）

### 6.1 指标由谁算

prompt 列出 SMA50/200、EMA10、MACD/Signal/Histogram、RSI、Bollinger、ATR、VWMA，建议选最多8个互补指标。get_stock_data 返回 OHLCV CSV，get_indicators 默认调用 Yahoo market 模块；模块通过 load_ohlcv→stockstats.wrap→df[indicator] 触发库计算，再输出逐日数值。[技术角色完整职责](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/analysts/market_analyst.py#L21-L81) [stockstats 真正计算处](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/market.py#L205-L233)

因此 MACD、RSI 的数值不是 LLM 算的，更不是模型训练预测价格。LLM 的工作是挑指标、解释形态、讨论冲突和风险。公式和默认平滑参数依赖 stockstats 库，不能仅凭项目文字宣称已经审计公式细节；Java 若更换指标库，需对照固定 OHLCV 样本和预热期验证数值等价。

可选 Alpha Vantage 路径直接调用 SMA/EMA/MACD/RSI/BBANDS/ATR 接口，并挑出对应 CSV 列和日期窗口。[外部指标 API](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/alpha_vantage/indicator.py#L85-L189)

### 6.2 Verified snapshot 做了什么

独立工具 get_verified_market_snapshot 直接读取 Yahoo OHLCV，不经过通用指标 vendor router；关闭 fill_gaps，再次过滤日期，确定性计算固定常用指标，返回最新交易行及最近最多30行收盘值。[快照核心](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/snapshot.py#L31-L128)

技术 prompt 要求报告前调用快照、把精确数值以快照为准，并对矛盾作说明。但“先调用行情”“最多8指标”“必须调用快照”都是 prompt 指令，不是子图强制前置条件或报告后数值验证器。不能称报告所有数字已由程序验证。更换通用指标 vendor 后，快照仍走 Yahoo，也需要解释来源和复权口径差异。

### 6.3 行情历史与缓存

load_ohlcv 下载以今天为基准最近5年的 Yahoo auto_adjust=True 数据，按 ticker 缓存，再过滤到 as_of_date；有缓存 TTL/过期检测、stale 数据拒绝、缺口处理。历史行过滤防止直接看到未来行，但不能单凭此证明重述/复权后的历史值就是当时可见的完整 vintage，且超过下载覆盖范围的历史不保证可用。[行情获取与日期过滤](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/vendors/yahoo/ohlcv.py#L161-L250)

## 7. 数据工具边界和错误语义

证券 symbol/ticker 与 trade_date 用 InjectedState 注入，不由模型自由改写；请求日期再与 trade_date 裁剪/取小。这样“你只能研究本次证券、不能查询分析日之后的数据”有代码边界，而非只写提示词。[股票、指标工具日期与身份](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/tools.py#L18-L94) [新闻、宏观日期](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/agents/tools.py#L171-L215)

供应商配置工具级覆盖类别级；显式 vendor list 就是实际 fallback chain，不偷偷启用未选择供应商。router 区分 VendorUnavailable、NoMarketData、NotConfigured；全部不可用返回 DATA_UNAVAILABLE，全部无数据返回 NO_DATA_AVAILABLE；macro/prediction 为可选增强，失败可以降级，其余未分类真实错误可能抛出。[路由与降级](https://github.com/TauricResearch/TradingAgents/blob/c4544913a14ed3df5f1ed7b9e8483270ce20682a/tradingagents/dataflows/router.py#L160-L297)

返回错误字符串供模型解释不等于结构化完整错误协议；还要防止模型把“获取失败”写成“利空/零收入/没有讨论”。

## 8. A 股 Java 版如何取舍（设计推导，不是原项目已实现）

| 模块 | 可保留的 Agent 机制 | A 股必须重新落地的部分 |
| --- | --- | --- |
| 基本面 | 白名单工具、调用循环、报告交接 | Tushare/合规供应商财报与估值、证券身份、披露日期、报表单位、单季与累计口径；SEC 实现不能直接用于 A 股 |
| 情绪 | 程序预取→可选过滤→结构化归纳，区分观点与事实 | StockTwits/Reddit 对 A 股覆盖不能假定有效；国内社区需合法可用接口/授权与样本覆盖说明，未接入就显示不可用，不编造情绪分 |
| 新闻 | 个股/宏观双层检索、日期过滤、来源与摘要 | 中文公司别名与同名消歧、公告与新闻分开、人民银行/统计局等时点口径；FRED 可作外部宏观补充 |
| 技术 | 确定性指标工具+模型解释+快照验证 | A 股交易日历、停牌、涨跌停、前后复权、数据源与指标参数一致；不让 LLM 做数值计算 |
| 编排 | 私有子上下文、并行汇合、有界循环、结构化交接 | Spring AI Alibaba 实际API适配、共享预算与取消；预取/Jev/重试/摘要全部纳入账本，不能只数 Agent 外层调用 |

## 9. 读者在四类分析师里分别能学到什么

- 基本面：工具抽象、数据适配器、模型解释与确定性计算的职责边界、point-in-time 事实。
- 情绪：Workflow 与 Agent 的区别、程序预取与工具自治的选择、结构化输出及其回退边界、采样偏差与缺数据表达。
- 新闻：检索与来源、时间窗口、可选工具降级、外部文本信任边界；无需先构建完整向量RAG才能有价值。
- 技术：模型选工具、数值计算外置、可重复快照、供应商口径一致与约束是否真正执行。
- 四角色协作：并行子图、消息隔离、state contract、汇合与终止；“四个不同prompt”只是角色定义起点，不等于多Agent全部工程。

## 10. 本次没有证明的结论

未在线验证 StockTwits/Reddit/Yahoo/FRED/Polymarket 接口可用性或商业授权；未运行完整模型分析；未验证这些 prompt 能稳定遵从、情绪分有效、形态预测准确或收益优于单 Agent。未审计 stockstats 上游公式。源码观察不作为“实盘可用”结论。
