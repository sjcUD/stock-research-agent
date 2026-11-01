# Stock Research Agent

面向系统学习 Agent 的 Java 股票研究项目，以 A 股为背景，采用 Maven 多模块、Spring AI 和 Spring AI Alibaba。源码保持 Java 8 风格，运行依赖使用 JDK 21；类、字段、方法和关键算法使用中文注释。

## 当前能力

- 单分析师研究：问题 → RAG 检索 → 基本面回答 → 引用来源。
- 多分析师研究：基本面、技术面、新闻、情绪、风险五个独立角色 → 组合经理汇总；支持串行和并行对照。
- 市场数据：腾讯未复权日线、东方财富最近新闻和公告列表，服务端计算 SMA、RSI、MACD，保留来源、时间、响应指纹与失败状态。
- RAG：文档解析、切片、真实 Embedding、Qdrant 写入与检索、Citation 回传。
- 解析器支持 PDF、Word、Excel、HTML、纯文本和 Markdown；目前以文本提取为主，OCR、精确页码与表格结构尚未完善。
- Harness：调用次数预算、估算 Token 预算、截止时间检查、有限重试基础能力；多角色共享请求预算，超时和失败触发其他任务取消。
- MySQL：工作台会话、用户/助手消息、任务状态、阶段事件、引用与角色轨迹归档；重启标记未完成任务为中断，查询不重放模型。
- Spring AI Alibaba `ReactAgent` 最小工厂及工具循环学习探针。
- 本地部署：三服务Compose、可执行JAR、启动/检查/停止/显式清理脚本、存活/就绪检查和日志脱敏。

当前多分析师是提示词角色，调度采用 Java 执行器，尚未通过 Alibaba Graph 构建完整协作图。P9 已接入日线、技术指标及最近资讯；完整财报、实时分时和社交情绪样本尚未接入。只有一个收盘价时，角色应说明无法计算 MACD、RSI 等指标。它们不会自动获得不存在的数据，也不会执行真实交易。

已接通过真实 ChatModel、Embedding、Qdrant 和 MySQL 的基础联调。真实日线已接入；P10 浅色聊天工作台已实现；MySQL模式可从服务端恢复会话，浏览器保留有限缓存；P7上下文压缩已接入研究链路。完整进度与后续工作见 [TODO.md](TODO.md)，模型兼容性经验见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

## 模块

| 模块 | 职责 |
| --- | --- |
| stock-agent-contracts | 跨模块公共契约 |
| stock-agent-data-contracts | 数据模式、提供器接口、日线、资讯与可追溯数据分组 |
| stock-agent-data-provider | SAMPLE/LIVE 策略、HTTP/限流、日线资讯适配及指标计算 |
| stock-agent-llm | Chat、Embedding、Rerank 网关和模型注册 |
| stock-agent-rag | 解析、切片、检索、引用和 Qdrant 适配 |
| stock-agent-research-agent | 工具循环、单角色与多角色研究 |
| stock-agent-runtime | Harness、预算和运行记录仓储 |
| stock-agent-evaluation | 固定数据集、RAG/回答/工具契约评测与参数对比报告 |
| stock-agent-web | Spring 装配和 HTTP API |

RAG 保持一个模块，内部通过接口隔离解析器、Embedding 和向量存储。MySQL 是业务记录存储，Qdrant 是首版向量存储。

## 运行

P12已提供三服务一键启动：先保留或从`.env.example`创建`.env`并配置模型，然后运行`./scripts/dev.sh start`，默认地址`http://127.0.0.1:8080`。`check`检查就绪，`stop`保留数据卷。Compose与以前的手工容器数据独立，详细步骤、端口冲突、恢复和清理见[本地部署说明](DEPLOYMENT.md)。

以下为宿主JVM开发运行方式：

要求 JDK 21、Maven 3.8+。先按 [.env.example](.env.example) 配置模型和 Qdrant，不要提交真实密钥。

```bash
mvn test
mvn -DskipTests install
set -a
. ./.env
set +a
export STOCK_AGENT_QDRANT_ENABLED=true
mvn -pl stock-agent-web spring-boot:run
```

应用默认地址：`http://localhost:8080`。打开根地址即可使用聊天工作台，前端与API同源，不需要启动独立Node服务。Qdrant 默认使用 gRPC 6334；如果本机 MySQL 端口为 3307，JDBC URL 需相应调整。数据库表结构见 `stock-agent-runtime/src/main/resources/db/migration/V1__baseline.sql`。

```text
GET  /api/market/data            服务端行情、指标、新闻与公告
POST /api/research/chat/market   无需用户报价的市场研究
POST /api/research/chat          单基本面分析师
POST /api/research/chat/multi    五角色分析 + 组合经理
POST /api/research/runs          创建研究运行记录（默认内存仓储）
POST /api/rag/documents          JSON 文档入库接口
GET  /api/rag/search?q=收入增长&topK=5
```

多角色接口示例，以下行情为 SAMPLE 演示数据：

```bash
curl http://localhost:8080/api/research/chat/multi \
  -H 'Content-Type: application/json' \
  -d '{"question":"请说明样例证据支持哪些判断、还缺什么数据", "symbol":"600519", "close":"1680", "asOf":"2026-10-30", "source":"SAMPLE", "mode":"PARALLEL"}'
```

将 `mode` 改为 `SERIAL` 可比较串行执行。两种模式都独立分析同一份证据，串行时不会把前一个角色的结论传给后一个角色。

响应包含 `mode`、实际 `engine`、`workflowState` 诊断、`decision`（经理汇总与五份角色报告）、`evidence`（包含 Citation）和 `traces`（角色、实际模型输入、输出、耗时毫秒）。引用列表代表本次检索获得的证据，不等于模型已逐条验证并使用。执行轨迹包含问题与证据原文，调用方应按研究内容管理访问权限。

支持 `thinking.type` 的供应商可配置 `STOCK_AGENT_CHAT_THINKING_MODE=disabled` 进行普通研究演示，或设为 `enabled` 并预留充足输出额度；留空时不发送扩展参数。真实 HTTP 验收使用 DeepSeek、2048 输出上限、显式 `disabled`。该开关通过 Spring AI `OpenAiChatOptions.extraBody` 透传，不另建 HTTP 网关。

默认每次多角色请求最多六次模型调用、120 秒总截止时间、60000 估算 Token 额度。父账本和分析师/经理阶段子账本原子预留调用与 Token；并非模型真实 usage 或精确费用上限。模型连接和正文读取均有 HTTP 超时，阶段剩余时间会收紧正文读取上限。任一角色失败即终止研究，不调用经理汇总残缺报告。

P7上下文策略保留当前问题和结构化行情事实，历史消息按最近优先裁剪，证据按分数选择完整片段并保留引用ID；不会调用摘要模型。`contextReport` 会随研究结果返回压缩前后 UTF-8 字节估算、保留/丢弃数量和移除的历史引用。该估算用于学习和预算保护，不等于供应商真实 Token usage。

## 通过 P8 学习什么

1. **职责隔离**：`ResearchAnalyst` 定义统一策略接口，`DefaultAnalystFactory` 组合五种职责；独立提示词避免角色共享可变聊天历史。
2. **上下文与证据传递**：问题、行情日期及来源、RAG 片段构成角色输入；缺失数据应显式报告。
3. **扇出与汇聚**：`MultiAnalystOrchestrator` 提供串行/并行模式，完成顺序用于发现失败，稳定角色顺序用于展示。
4. **共享预算与生命周期**：并行角色竞争同一预算；截止时间限制等待；任一失败取消其他任务；串行失败后不会再启动后续角色。
5. **综合判断**：`PortfolioManager` 接收所有报告和证据，保留分歧、不确定性及待核验条件。
6. **可观测性**：`AgentTrace` 保存实际输入、输出与耗时；工作台已独立归档完成角色轨迹，整体失败时保留此前角色；失败阶段记录状态与安全错误，不伪造模型输出。
7. **可替换设计**：网关隔离模型供应商，角色接口便于后续接入带工具的 Agent，向量存储接口隔离 Qdrant。

这一步实现的是可测试的多角色协作基础。自主工具选择、Graph 状态管理、多轮辩论、长期记忆和真实 usage 结算仍需各自独立实现和验证。

## 验证

`mvn test` 默认运行确定性测试，不需要模型密钥。外部集成测试显式启用：

```bash
# 先加载本地 .env，再选择所需测试
CHAT_INTEGRATION=true mvn -pl stock-agent-web -am \
  -Dtest=ChatModelLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
RAG_REAL_INTEGRATION=true mvn -pl stock-agent-web -am \
  -Dtest=RealRagQdrantIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
MULTI_RESEARCH_INTEGRATION=true STOCK_AGENT_QDRANT_ENABLED=true mvn -pl stock-agent-web -am \
  -Dtest=MultiResearchLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
QDRANT_INTEGRATION=true mvn -pl stock-agent-rag -am \
  -Dtest=QdrantLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

模型 Base URL 和路径兼容配置参见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。DashScope Embedding 保留完整 `/compatible-mode/v1` Base URL，并显式设置 `embeddingsPath("/embeddings")`。

P11 已增加固定合成数据集、检索参数对比、回答规则和工具调度契约评测；运行方式和学习入口见 [评测模块说明](stock-agent-evaluation/README.md)。P12本地运行与部署、P6会话归档和P7 Harness 完整运行时已完成。P11已补齐公开语料、语义模型评审、实际usage和盲评工具链；生产检索支持可配置最低分和可替换重排器。前端沿用已确定的浅色聊天交互设计。

## P9 市场数据与研究

默认 `STOCK_AGENT_DATA_MODE=SAMPLE`，只提供 `600519 / 2026-10-30` 的固定合成样例。样例日线包含合成日历天，不是交易日历或实际历史成交。其他标的和日期明确拒绝，不套用同一个报价。

在线模式独立于模型模式：

```bash
set -a
. ./.env
set +a
export STOCK_AGENT_DATA_MODE=LIVE
export STOCK_AGENT_QDRANT_ENABLED=true
# 仅对支持该参数的 DeepSeek 服务使用；其他服务按自己的能力配置
export STOCK_AGENT_CHAT_THINKING_MODE=disabled
mvn -pl stock-agent-web spring-boot:run
```

```bash
curl 'http://localhost:8080/api/market/data?symbol=600519&asOf=2026-10-30'
curl http://localhost:8080/api/research/chat/market \
  -H 'Content-Type: application/json' \
  -d '{"question":"请说明指标和资讯支持的判断及缺失信息，每个角色100字以内", "symbol":"600519", "asOf":"2026-10-30", "mode":"PARALLEL"}'
```

`asOf` 按上海时区解释。在线首版支持沪深 A 股，接受 `600519`、`sh600519`、`600519.SH` 等匹配身份；不接指数、北交所或美股。未来日期和当日16点前的日终请求拒绝；返回的实际行情日可能早于请求日。最新报价超过14天则拒绝并提示核查停牌或时效，不自动回退样例。

纯取数 API 不需要模型或 Qdrant。市场研究入口目前复用多角色服务装配，仍需配置 ChatModel 和启用 Qdrant，但该入口不检索当前没有披露时点标签的通用 RAG 文档，避免把样例或未来文档混入真实研究。原 `/api/research/chat` 与 `/api/research/chat/multi` 保留 RAG 学习链路。

市场研究响应包含 `marketData` 与 `research`。前者携带实际日线、计算指标、新闻/公告原文链接与分组来源；后者包含五角色报告、经理汇总和执行轨迹。分组状态区分 `AVAILABLE`、`EMPTY`、`UNAVAILABLE`：成功空结果不等于全市场没有事件，接口失败更不能当作没有事件。

数据边界：

- 日线采用腾讯最近180个日历天窗口的**未复权**人民币 OHLC，不读取响应附带的实时快照，也暂不暴露尚未统一单位的成交量。
- 新闻为东方财富关键词查询最近10条，公告为最近20条列表；再按研究日及最近30天过滤。它们不是完整历史资料库，历史研究可能得到空结果。公告按 `notice_date` 的日期保守过滤，不将较早的 `display_time` 当作精确披露时点；不自动下载公告全文。
- 来源响应的 SHA-256 标识本次抓取版本，`fetchedAt` 表示抓取时刻，均不能证明完整历史可得性或替代供应商发布版本。
- SMA5/20 使用窗口收盘价均值；RSI14 使用 Wilder 平滑，平盘约定50；MACD(12,26,9) 用窗口首日收盘价初始化 EMA，柱值为 `2*(DIF-DEA)`。未复权除权跳变和窗口初值都可能导致指标与行情软件不同。
- 核心行情失败会终止 LIVE 请求；新闻或公告失败以 `UNAVAILABLE` 显式降级。价格须为正且满足高低关系；金额文本限制为12位整数、6位小数，拒绝异常科学计数法膨胀。
- 默认连接3秒、读取空闲5秒、总网络截止8秒、响应上限1MiB；所有上游共享每分钟60次的发送间隔，排队超过1.5秒拒绝。总截止及线程中断会主动取消 HTTP 请求，每次请求释放连接和监控线程。
- 进入模型的新闻最多3条、公告最多5条，摘要截短明确标记；完整结构化结果仍返回给客户端。P7另有统一上下文压缩策略，负责历史和证据的配额分配。组合经理去重同一份共享证据，避免五角色重复放大输入。

网页数据适配可能随供应商协议变化。参考的是 [AKShare 腾讯日线接口说明](https://akshare.akfamily.xyz/data/stock/stock_quote.html)及[个股新闻说明](https://akshare.akfamily.xyz/data/stock/stock_research.html)，Java 通过 `HttpTextClient`、`StockDataProvider` 隔离传输和来源，不需要 Python 侧车。替换为其他数据源时，研究服务无需依赖新供应商 SDK。

显式真实数据测试与完整市场研究测试：

```bash
MARKET_DATA_INTEGRATION=true MARKET_DATA_AS_OF=2026-10-30 mvn -pl stock-agent-data-provider -am \
  -Dtest=LiveMarketDataIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
# 先加载模型 .env，并设置 LIVE、Qdrant 和供应商支持的思考模式配置
MARKET_RESEARCH_INTEGRATION=true MARKET_DATA_AS_OF=2026-10-30 mvn -pl stock-agent-web -am \
  -Dtest=MarketResearchLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

P9 对应的 Agent 学习点是：工具/数据提供器边界、确定性计算与模型推断分工、asOf 防未来信息、可追溯证据、部分失败与恢复策略、限流/截止/取消，以及给多角色选择有限上下文。取数由服务端编排完成，当前不宣称分析师已自主选择所有市场工具或具备完整历史回测能力。

## P10 聊天工作台

按已确认 v6 的浅色两栏实现。应用启动后访问 **http://localhost:8080/**。

- 在同一个对话中研究行情、指标与最近资讯，查看五位分析师和组合经理报告；研究过程默认折叠，事件来自真实后台执行位置。
- 证券与日期在一轮对话内保持一致；新建研究可调整范围。追问只携带同范围的有限前一轮背景，不将资料问答内容自动带进市场研究。
- 图表仅绘制后端实际返回日线，没有假基准或预测曲线；来源抽屉展示实际日期、抓取时间、覆盖限制和原文片段。
- 在研究资料面板上传文档、等待索引确认、检索片段，显式切换“资料问答”。资料问答不需要访问行情源，不承诺历史披露时点过滤。
- Markdown、表格和代码块经本地 Marked + DOMPurify 解析/净化；禁止远程图片及可执行HTML，来源链接只允许HTTP(S)。第三方版本与许可证位于 `static/vendor`。
- 可停止后台研究、重新连接同一个任务ID、明确重试，或下载Markdown报告。POST不自动重试；轮询断开不会偷偷创建新的收费任务。取消发送中断信号并禁止发布迟到结果，底层SDK是否立即退出仍取决于其实现。

MySQL模式会话从服务端恢复，浏览器仅缓存最近8个会话、每个最近10轮；旧浏览器记录保留并可本地导出，MySQL缓存不覆盖它们，不自动导入。未启用MySQL时为显式进程内归档。最多2个研究同时运行、8个排队、100个执行对象缓存，创建后3分钟截止、终态缓存20分钟；MySQL历史独立保留，重启后未完成任务标记INTERRUPTED，不自动续跑。当前使用轮询进度，不是逐Token流式生成。详见[会话与任务归档说明](PERSISTENCE.md)。

RAG 检索默认使用 `STOCK_AGENT_RAG_SEARCH_MINIMUM_SCORE=0.0`，先过滤低于阈值的命中，再按分数和稳定片段ID重排；设置更高阈值可减少无答案噪声，但应先用 P11 评测确认召回损失。重排器通过 `RetrievalReranker` 接口替换，当前实现不修改引用正文。

文档单文件上限10MiB，文本提取最多100万字符，超过明确拒绝并提示拆分；不静默只索引前10万字符。支持PDF、Word、Excel、HTML、TXT、Markdown，扫描图片/OCR和精确页码定位尚未完成。Tika已更新至3.3.3并验证PDF、DOCX、XLSX及长文本；原JSON入库与同步研究API继续保留。

工作台接口：

```text
GET    /api/workspace                  配置能力与默认研究日期（不返回密钥）
POST   /api/research/tasks             创建后台任务，scope=MARKET或DOCUMENTS
GET    /api/research/tasks/{id}        查询实时/已归档状态、结果和独立角色轨迹
GET    /api/conversations             分页历史会话
GET    /api/conversations/{id}        完整轮次
GET    /api/conversations/{id}/messages 用户和助手消息
DELETE /api/research/tasks/{id}        显式取消
POST   /api/rag/documents/upload      multipart文件上传，file字段
GET    /api/rag/search                检索已索引资料
```

前端源码位于 `stock-agent-web/src/main/resources/static`：`index.html`、`assets/style.css`、`assets/app.js`（交互）、`assets/api.js`（API与独立取消通道）、`assets/workspace.js`（可测试状态与图表逻辑）。静态ES模块由Spring Boot提供，不需要前端构建或CDN。

运行与验证：

```bash
mvn test
# Node18+仅用于前端测试，运行页面不需要Node或npm install
npm run test:ui
```

P10可学习：异步任务与领域执行分离、真实进度观察、排队背压、终态不可回退、取消与迟到结果竞争、断线重连/幂等边界、有限上下文选择、证据展示与HTML净化。它没有宣称实现Graph状态持久化。

## P11 评测与质量验证

`stock-agent-evaluation` 将检索指标、引用回链、回答规则与实际应用工具轨迹分别评分。固定8篇合成资料和16个问题，支持不依赖密钥的18组离线配置，以及显式启用的真实 Embedding/Qdrant/ChatModel 集成评测；JSON/Markdown 报告保存在被忽略的 `evaluation-reports/`。

```bash
mvn -pl stock-agent-evaluation -am test -Dstock.eval.report="$PWD/evaluation-reports"
```

本阶段同时修复重试只扣一次 Token、取消前先扣预算、Embedding 单次批量超过已验证供应商限制，以及回答引用格式不明确的问题。调用和 Token 原子预留，失败尝试仍扣估算预算；Embedding 和 Qdrant 写入默认按10条分批，可通过适配器构造参数配置。Spring AI 继续负责协议和路径处理。

指标口径与真实服务命令见 [评测模块说明](stock-agent-evaluation/README.md)。当前工具评测是确定性调度契约，回答评分是规则检查；都不等于模型自主工具能力或投资效果。无答案检索仍可能返回无关片段，已在报告单列，尚未引入相似度阈值或重排拒答策略。

## P6 会话持久化与重启恢复

工作台`/api/research/tasks`已接入可替换归档策略。MySQL启用时，入队前事务保存问题和任务，完成时事务保存助手正文与终态，独立保存已完成角色的实际输入、输出和耗时。服务重启只恢复历史并中断未完成任务，不自动重放模型。Compose自动启用MySQL；宿主运行需配置`STOCK_AGENT_MYSQL_ENABLED=true`及有效连接。

学习入口、数据表、API、存储故障和单活边界见[PERSISTENCE.md](PERSISTENCE.md)。保存消息与模型上下文压缩分别有独立边界：前者负责可恢复归档，后者负责本轮提示词预算和证据选择。


P11收口复现入口为 `scripts/evaluate.sh offline|live|blind`，详见[评测说明](stock-agent-evaluation/README.md#p11-收口验收与复现)。包含三套语料的216组参数对照、可回链片段标注、模型重排、逐结论语义裁判、真实ReactAgent工具选择/两轮追问/暂时失败恢复，以及actual usage、显式费率估算和延迟分布。模型评分与人工评分分开；人工盲评工具链已交付，未发生的人类采样不会被标为已完成。生产规模压测、账单对账与更多财报语料按需要扩展。


## P8 Graph引擎对照

已增加真实Spring AI Alibaba StateGraph执行路径。`mode`仍为`SERIAL`或`PARALLEL`；在多角色、托管市场研究与后台任务API中另传`engine=ALIBABA_GRAPH`可切换。省略engine继续使用`JAVA_EXECUTOR`。相同证据/角色/预算跨引擎复用，Graph响应包含实际状态键与已完成角色，工作台请求/结果归档保留引擎信息。

学习入口见[GRAPH.md](GRAPH.md)：节点与边、基础类型状态、并行独立状态键、经理汇聚、取消/预算和真实测试。当前只实现单次图状态，未提供跨进程Saver恢复；现有UI和重启中断语义保持不变。


P8已进一步完成条件路由、基于服务端成功归档的局部重跑与真实usage预算结算。`POST /api/research/tasks/{id}/rerun`传`{"roles":["NEWS"]}`仅重跑新闻与经理，传`{"roles":[]}`仅重跑经理；固定原问题与证据，保留`sourceTaskId`，不覆盖原任务。父/子账本按可信usage替换本次预估，未知usage保守预留。完整学习说明、接口限制与计费边界见[GRAPH.md](GRAPH.md)。持久Saver跨进程续跑仍为独立选修，未冒充已完成。


### P10：增量研究与文档复核

后台研究任务通过同一个任务 ID 提供 SSE 快照；浏览器断流后回退到状态查询，不会重复创建模型任务。模型网关使用 Spring AI 原生流式接口，前端展示未完成草稿，只有任务完成才发布最终答案。每个任务共用预算和截止时间，取消会关闭流订阅并释放后台执行资源。

RAG 文档默认归档到 `data/documents`。PDF 按实体页解析并保留空白页编号，扫描 PDF 和 PNG/JPEG 在配置 Tesseract 与 `chi_sim+eng` 后执行有界 OCR；Office、HTML、文本文件保留逻辑全文，不虚构物理页。原文接口只接受服务端生成的文档 ID/版本，HTML 原件以附件返回，PDF 可按页打开，引用片段会在页内精确匹配时高亮。

本地运行 OCR 需要安装 Tesseract 及语言包；Compose 镜像已包含 OCR 依赖，并使用 `document-data` 数据卷保存原文。限制为单文件 10MiB、PDF 100 页且最多 20 页 OCR、单图 20MP、提取文本 100 万字符。OCR 结果仅供检索，阅读结论时应对照原件。

### P9 数据口径增强

市场数据接口支持 `adjustment=UNADJUSTED|FORWARD_ADJUSTED|BACKWARD_ADJUSTED`，研究请求也可指定复权口径。LIVE 提供器通过腾讯日线接口读取对应序列；成交量在进入领域模型前统一为 `SHARES`。复权与未复权序列按交易日比较，只有价格比值发生突变时才记录公司行为因子，并标记为“类型待核验”，不会把价格跳变擅自解释成分红或送转。

LIVE 结果使用证券代码、研究截止日和复权口径作为 point-in-time 缓存键，缓存有界且带 TTL，避免不同研究日相互污染。资讯仍是最近窗口；财报全文、公告全文和社交情绪数据保持明确未接入状态。


P9 剩余数据源边界：`FinancialStatement` 和 `SentimentSnapshot` 已加入领域契约，结果包含 `UNAVAILABLE` 状态和原因，只有披露日不晚于研究日的数据才允许进入上下文。公告全文通过 `STOCK_AGENT_DATA_ANNOUNCEMENTS_FULL_TEXT=true` 开启，默认关闭；全文会被净化为纯文本并限制长度，列表失败与全文失败分别记录。真实财报和社交情绪供应商仍需按项目实际授权接入。


### P12 运行验收

`./scripts/dev.sh start` 使用独立 Compose 项目启动 MySQL、Qdrant 和应用，应用端口只绑定本机回环地址。`check` 检查 readiness，`stop` 保留命名卷，`clean --delete-data --confirm=stock-research-agent` 才删除当前项目数据。构建阶段采用非 root 运行镜像，包含 Tesseract 中文语言包和原文数据卷。
