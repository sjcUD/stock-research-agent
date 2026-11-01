# P11：从“能运行”到“可验证”

本模块是独立的评测工具，不参与 Web 运行时装配。通过业务接口接入检索、Embedding 与聊天服务，用固定输入发现回归。源码使用 Java 8 风格，运行基线 JDK 21。

## 可以学习的 Agent 知识

|部分|学习重点|实现入口|
|---|---|---|
|版本化数据集|把人工标注与模型输入隔离；保留版本和内容哈希|`EvaluationDataset`|
|检索指标|文档级 Recall@K、Hit@K、MRR；多文档问题区分召回与命中|`RagEvaluator`|
|引用验证|来源、版本、页码、偏移、片段标识与原文一致性|`RagEvaluator.validCitation`|
|参数实验|每次改变模型或维度重建索引；只改变需要比较的配置|`EvaluationSuite`、`EvaluationVectorIndex`|
|回答评测|区分关键事实、证据不足表达、引用语法；保留正例与反例|`AnswerEvaluator`|
|工具评测|从执行位置采集实际名称/参数/成功状态；重复调用不能重复得分|`ToolCallEvaluator`、`scriptedWorkflow`|
|Harness 回归|每次尝试扣预算、取消前置检查、原子预留、认证失败不重试、退避截止|runtime 的 `HarnessCallExecutorTest`|
|真实集成|用生产 provider 配置验证，使用独立 collection 和实际生成的数据库主键|web 的 `EvaluationLiveIntegrationTest`、runtime 的 MySQL 集成测试|

## 数据集与指标口径

基础固定 JSON 位于 `src/main/resources/evaluation/stock-agent-synthetic-v1.json`。另有困难近邻数据集 `stock-agent-synthetic-v2-hard.json`，专门覆盖同数值不同主体、否定条件、时间口径和无答案组合。基础数据集包含 **8 篇虚构公司资料、16 个问题**（14 个有答案、2 个无答案），包含财务、审计、分红条件、收购审批、指标、新闻样本与研发投入；含跨文档问题和措辞变化。全部为合成事实，不能据此评估投资效果。

- Recall@K：本次前 K **片段**覆盖的相关文档数 / 该问题相关文档总数，正例宏平均。
- Hit@K：是否找到至少一篇相关文档，正例宏平均。
- MRR：对返回片段按文档去重，首篇有效相关文档的倒数排名，正例宏平均。这里的排名是文档排名，不是原始片段排名。
- Citation coverage：可按完整定位回到原文的片段数 / 所有命中片段数。检查版本、页码1、UTF-16偏移、稳定片段ID及文字；没有命中时为 null。引用结构正确不代表语义支持当前结论。
- `negativeAnyHitRate`：无答案问题中仍返回任意片段的比例，单列，越低越好。它不是有答案问题的 Hit；检索失败也按负例失败处理。可通过 `EvaluationSuite.offlineWithThreshold(threshold)` 做相似度阈值实验，阈值采用大于等于比较，边界命中可复现。历史基线无阈值，因此可能为1；当前生产支持显式配置最低分，仍需评估正例召回损失。
- 查询异常记录为 ERROR，正例分母仍保留，不能只统计成功查询。报告保留每题原始命中、配置、错误类型、数据集哈希、模式和时间；不写供应商异常正文或环境变量。

合成基线的相关性标注是**文档级**，某片段来自相关文档不保证它包含回答所需事实。公开语料另有逐字片段标注，`evidenceRecallAtK`只有在有效引用区间连续覆盖整段标注时得分，旧数据集此项为null。`AnswerEvaluator.faithfulness` 额外按事实字符串和引用片段做确定性覆盖检查：统计已回答且被引用正文支持的事实、未被支持的事实和未知引用；这是证据覆盖回归，不是 LLM-as-judge。长文档中的细粒度证据覆盖、语义忠实度、人工复核、统计显著性都需要更大数据集。小样本满分不能证明模型普遍优劣。

## 离线评测

在仓库根目录执行，不需要密钥或容器：

```bash
mvn -pl stock-agent-evaluation -am test \
  -Dstock.eval.report="$PWD/evaluation-reports"
```

生成 `evaluation-reports/offline.json` 和 `offline.md`，目录已加入 Git 忽略规则。默认 `mvn test` 也运行评测回归，但不写报告。

18组配置：**非语义字符二元组哈希基线**的32/128维 × 32/64/128字符切片 × topK 1/3/5。该基线只验证指标与流水线，不是可用的语义 Embedding。固定排序避免同分随机抖动。每个配置独立索引，绝不复用不同维度/模型的向量。

回答离线测试使用明确标识的脚本模型，检查生产 `ResearchChatService` 是否传递真实问题与证据，同时包含错数字、未知引用、无引用和不拒答等反例。它不评估真实模型泛化能力。工具分数检查的是**确定性应用调度契约**，当前尚未实现模型自主工具选择；不能将这份报告称为 LLM 选工具准确率。对期望和实际轨迹做一对一匹配，输出 precision/recall/F1 及完整顺序是否一致。

## 真实服务评测

先加载自己的 `.env`。真实调用会消耗模型额度，仅在显式启用时运行：

```bash
set -a
. ./.env
set +a
export EVALUATION_INTEGRATION=true
# 模型和维度须为你的服务确实支持的组合；省略时使用 .env 单组配置。
export EVAL_EMBEDDING_MODELS=text-embedding-v3,text-embedding-v4
export EVAL_EMBEDDING_DIMENSIONS=512,1024
# 仅在供应商支持此扩展时设置，其他供应商保持自己的配置。
export STOCK_AGENT_CHAT_THINKING_MODE=disabled
mvn -pl stock-agent-web -am test \
  -Dtest=EvaluationLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dstock.eval.report="$PWD/evaluation-reports"
```

生成 `live.json`、`live.md`。上述组合产生36组内存余弦检索结果；这不是36组 Qdrant 性能压测。同一模型/维度内部缓存相同文本的 Embedding，减少重复付费，缓存绝不跨 profile；目前不记录延迟或费用排名。

另用第一个成功配置，在本地 Qdrant gRPC 6334（可设 `EVAL_QDRANT_HOST/PORT`）创建 UUID 测试 collection，执行真实写入、全问题检索和四个实际聊天回答检查。测试结束只删除自己生成的 collection，保留用户业务索引。测试调用生产 provider 配置，不另建 HTTP 网关。实际返回维度必须与配置完全一致；不支持的组合会记录失败，不静默跳过。

真实回答也仅检查必含事实、证据不足表达、`[片段=实际片段ID]` 的合法性。引用 ID 必须来自本次实际命中。它无法检测所有额外幻觉或证明每句话被证据支持。

独立 MySQL 写入、读回与清理：

```bash
export MYSQL_INTEGRATION=true
export STOCK_AGENT_MYSQL_URL=jdbc:mysql://127.0.0.1:3307/stock_agent
export STOCK_AGENT_MYSQL_USER=stock
# 从你的安全配置中设置 STOCK_AGENT_MYSQL_PASSWORD，不提交到仓库。
mvn -pl stock-agent-runtime -am test \
  -Dtest=JdbcResearchRunRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

MySQL 用户需具备测试表的建表、插入、查询、删除权限。测试复用现有表结构，使用自动生成的会话/运行主键，结束时只删除本次记录，不清空表。外部服务测试默认跳过；启用后缺配置或服务不可用应失败。

## 人工盲评

可用 `BlindReviewTemplate.write(path, EvaluationDataset.load("/evaluation/stock-agent-synthetic-v2-hard.json"))` 生成不含标准答案、相关文档和事实标注的 CSV。评审者只填写 `answer`、`citation_ok`、`faithful` 和 `notes`，结果再与机器规则分数分开分析，避免把标注泄漏给回答者。`BlindReviewAggregator` 会校验七列格式，统计引用通过率、忠实度通过率，并在有多位评审者时计算逐问题成对一致率。

## 当前验收边界

基础评测与下文P11收口能力已实现；真实人类采样、独立裁判模型对照、更多公司年报和大规模生产压测可按实际需要开展，不把模型裁判当作人工评分。P6会话归档与P7运行时见根目录说明。

## P11 收口验收与复现

P11按常规学习项目收口：可追溯数据、参数对照、逐结论模型评审、只读Agent轨迹、usage和延迟观测都能独立运行。新增能力不是交易预测准确率，也不是生产压测认证。

```bash
# 无密钥：三套语料，每套72组配置，另有30轮重复执行观测
scripts/evaluate.sh offline
# 外部公开语料采用与固定数据集相同的JSON结构，最大20MiB
scripts/evaluate.sh offline -Dstock.eval.dataset=/absolute/path/dataset.json

# 真实模型与Embedding：先自行加载.env；仅live显式调用会付费
set -a
. ./.env
set +a
# DeepSeek演示可显式关闭thinking；其他供应商按其支持能力配置
export STOCK_AGENT_CHAT_THINKING_MODE=disabled
scripts/evaluate.sh live

# 收集真正的人类评分后再执行；不得把测试fixture当作人类盲评
scripts/evaluate.sh blind "$PWD/evaluation-reports/p11-public-live.json" /absolute/path/reviewer1.csv,/absolute/path/reviewer2.csv
```

新增 `public-education-v1`：4份官方投教事实短摘要、12题（含2道无答案），保留源链接、核验日期和逐字可回链证据标注及独立片段召回指标。来源为[上交所科创板投教问答](https://edu.sse.com.cn/tib/qa/)与[深交所2020年创业板投教问答](https://www.szse.cn/www/investor/index/update/t20200729_580056.html)。它是固定历史资料快照，摘要为项目独立改写，不是当前交易规则查询服务，也不复制整篇文章。v1合成语料不修改，旧哈希继续有效。

- `SemanticJudge` / `ModelSemanticJudge`：独立模型调用拆解完整回答中的结论，核查主体、时间、否定、额外预测和遗漏事实；每条结论必须逐字回到回答、每个引用必须存在。JSON错误或无法回链直接失败，不转换成“通过”。这是模型裁判，需要人工抽检；同模型生成与评审可能有共同偏差。
- `ModelRetrievalReranker`：可注入现有 `RetrievalReranker` 接口的真实模型重排实验。输出与候选一一对应的0至1分数，证据正文和Citation不能由模型生成；无效输出直接失败。分数为重排相关性，不与原向量分数混用。未默认启用到生产，也未凭一个案例声称效果优于向量检索。
- 资料问答可配置 `STOCK_AGENT_RAG_SEARCH_ABSTAIN_WHEN_EMPTY=true`、`STOCK_AGENT_RAG_SEARCH_MINIMUM_HITS=1`。阈值过滤后证据不足会在调用回答模型前返回明确“资料不足”，避免额外费用；默认false保持已有行为。语料无法回答但仍返回高分片段时，这个策略不能自动识别所有情况。
- `SpringAiChatModelGateway.complete`：返回供应商实际输入/输出Token；缺失usage为null。`MeasuredChatGateway`记录每次调用耗时、失败类型和usage，不记录提示词或供应商异常正文。可设置 `P11_INPUT_PRICE_PER_MILLION`、`P11_OUTPUT_PRICE_PER_MILLION`、`P11_CURRENCY` 生成显式费率估算；未提供价格时费用为null，缓存/分档等实际计费不推断，不等于供应商账单。
- `EvaluationStatistics`：P50/P95采用最近秩；成功率提供Wilson 95%区间。ERROR仍在分母。重复同一数据的30轮用于学习稳定性观测，不能冒充30份独立语义样本或生产吞吐结论。
- `P11CompletionLiveIntegrationTest`：真实Spring AI Alibaba ReactAgent面对两个只读样例工具，由模型自主选工具；核查实际symbol参数，注入一次可重试错误并验证恢复，再进行同证券第二轮追问。记录真实工具执行轨迹；它只评估这个受控任务，不宣称已把工作台编排迁移为Graph。

报告文件：三套 `*-acceptance.json/md`、`p11-public-live.json/md`、`p11-tool-trajectory.json`，均在忽略目录。真实模型输出只用于本地复核，不提交。历史P11报告是旧数据/旧策略结果，不能与新语料的指标混算。

人工盲评已交付模板、候选预填、格式校验、同一数据集/问题/候选约束、通过率与成对一致率、人工-机器对照报告；机器报告缺少候选清单、问题/回答指纹不一致时拒绝拼接。`BlindReviewTemplate.write(path, dataset, answersByQuestionId)` 可预填匿名回答；不要让评审者改写候选。标准答案不进入表格；核验引用另行提供 `p11-public-live-evidence.json` 中的实际证据，不要把包含标准事实的完整机器评分发给盲评者。没有真实评审者填写时不生成虚构评分；当前仓库未宣称已完成人类盲评采样。
