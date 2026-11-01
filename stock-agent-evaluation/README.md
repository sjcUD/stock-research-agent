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

固定 JSON 位于 `src/main/resources/evaluation/stock-agent-synthetic-v1.json`。包含 **8 篇虚构公司资料、16 个问题**（14 个有答案、2 个无答案），包含财务、审计、分红条件、收购审批、指标、新闻样本与研发投入；含跨文档问题和措辞变化。全部为合成事实，不能据此评估投资效果。

- Recall@K：本次前 K **片段**覆盖的相关文档数 / 该问题相关文档总数，正例宏平均。
- Hit@K：是否找到至少一篇相关文档，正例宏平均。
- MRR：对返回片段按文档去重，首篇有效相关文档的倒数排名，正例宏平均。这里的排名是文档排名，不是原始片段排名。
- Citation coverage：可按完整定位回到原文的片段数 / 所有命中片段数。检查版本、页码1、UTF-16偏移、稳定片段ID及文字；没有命中时为 null。引用结构正确不代表语义支持当前结论。
- `negativeAnyHitRate`：无答案问题中仍返回任意片段的比例，单列，越低越好。它不是有答案问题的 Hit；检索失败也按负例失败处理。当前业务无分数阈值，不应期待它自动为0。
- 查询异常记录为 ERROR，正例分母仍保留，不能只统计成功查询。报告保留每题原始命中、配置、错误类型、数据集哈希、模式和时间；不写供应商异常正文或环境变量。

目前相关性标注是**文档级**，某片段来自相关文档不保证它包含回答所需事实。长文档中的细粒度证据覆盖、语义忠实度、人工复核、统计显著性都需要更大数据集。小样本满分不能证明模型普遍优劣。

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

## 尚未覆盖

真实工具自主选择、LLM-as-judge、人工盲评、多轮长期任务、完整历史时点回放、吞吐压测、实际 usage 结算未实现；工作台轨迹已在P6归档，具体边界见根PERSISTENCE.md。P6会话归档已补齐；P7模型HTTP超时、阶段预算、上下文压缩仍在根TODO中，不因评测完成而自动完成。
