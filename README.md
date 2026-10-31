# Stock Research Agent

面向系统学习 Agent 的 Java 股票研究项目，以 A 股为背景，采用 Maven 多模块、Spring AI 和 Spring AI Alibaba。源码保持 Java 8 风格，运行依赖使用 JDK 21；类、字段、方法和关键算法使用中文注释。

## 当前能力

- 单分析师研究：问题 → RAG 检索 → 基本面回答 → 引用来源。
- 多分析师研究：基本面、技术面、新闻、情绪、风险五个独立角色 → 组合经理汇总；支持串行和并行对照。
- RAG：文档解析、切片、真实 Embedding、Qdrant 写入与检索、Citation 回传。
- 解析器支持 PDF、Word、Excel、HTML、纯文本和 Markdown；目前以文本提取为主，OCR、精确页码与表格结构尚未完善。
- Harness：调用次数预算、估算 Token 预算、截止时间检查、有限重试基础能力；多角色共享请求预算，超时和失败触发其他任务取消。
- MySQL：运行记录 JDBC Repository 和迁移脚本；Web 创建运行接口当前默认使用内存仓储，多角色轨迹只随响应回传。
- Spring AI Alibaba `ReactAgent` 最小工厂及工具循环学习探针。

当前多分析师是提示词角色，调度采用 Java 执行器，尚未通过 Alibaba Graph 构建完整协作图。技术指标、实时新闻和社交情绪数据工具将在 P9 接入。只有一个收盘价时，角色应说明无法计算 MACD、RSI 等指标。它们不会自动获得不存在的数据，也不会执行真实交易。

已接通过真实 ChatModel、Embedding、Qdrant 和 MySQL 的基础联调。聊天前端、会话持久化、真实行情和接入聊天链路的上下文压缩尚未完成。完整进度与后续工作见 [TODO.md](TODO.md)，模型兼容性经验见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

## 模块

| 模块 | 职责 |
| --- | --- |
| stock-agent-contracts | 跨模块公共契约 |
| stock-agent-data-contracts | 数据模式等领域契约 |
| stock-agent-data-provider | SAMPLE 行情与数据提供器边界 |
| stock-agent-llm | Chat、Embedding、Rerank 网关和模型注册 |
| stock-agent-rag | 解析、切片、检索、引用和 Qdrant 适配 |
| stock-agent-research-agent | 工具循环、单角色与多角色研究 |
| stock-agent-runtime | Harness、预算和运行记录仓储 |
| stock-agent-web | Spring 装配和 HTTP API |

RAG 保持一个模块，内部通过接口隔离解析器、Embedding 和向量存储。MySQL 是业务记录存储，Qdrant 是首版向量存储。

## 运行

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

应用默认地址：`http://localhost:8080`。当前提供 API，尚无完整聊天页面。Qdrant 默认使用 gRPC 6334；如果本机 MySQL 端口为 3307，JDBC URL 需相应调整。数据库表结构见 `stock-agent-runtime/src/main/resources/db/migration/V1__baseline.sql`。

```text
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

响应包含 `mode`、`decision`（经理汇总与五份角色报告）、`evidence`（包含 Citation）和 `traces`（角色、实际模型输入、输出、耗时毫秒）。引用列表代表本次检索获得的证据，不等于模型已逐条验证并使用。执行轨迹包含问题与证据原文，调用方应按研究内容管理访问权限。

支持 `thinking.type` 的供应商可配置 `STOCK_AGENT_CHAT_THINKING_MODE=disabled` 进行普通研究演示，或设为 `enabled` 并预留充足输出额度；留空时不发送扩展参数。真实 HTTP 验收使用 DeepSeek、2048 输出上限、显式 `disabled`。该开关通过 Spring AI `OpenAiChatOptions.extraBody` 透传，不另建 HTTP 网关。

默认每次多角色请求最多六次模型调用、120 秒总截止时间、60000 估算 Token 额度。分析师按完整提示词字符数加 2048 输出预留估算，经理按报告及证据文字保守估算；并非模型真实 usage 或精确费用上限。任一角色失败即终止研究，不调用经理汇总残缺报告。Future 取消会发送线程中断；底层网络请求是否立即退出取决于 SDK，HTTP 读取超时配置仍在待办中。

## 通过 P8 学习什么

1. **职责隔离**：`ResearchAnalyst` 定义统一策略接口，`DefaultAnalystFactory` 组合五种职责；独立提示词避免角色共享可变聊天历史。
2. **上下文与证据传递**：问题、行情日期及来源、RAG 片段构成角色输入；缺失数据应显式报告。
3. **扇出与汇聚**：`MultiAnalystOrchestrator` 提供串行/并行模式，完成顺序用于发现失败，稳定角色顺序用于展示。
4. **共享预算与生命周期**：并行角色竞争同一预算；截止时间限制等待；任一失败取消其他任务；串行失败后不会再启动后续角色。
5. **综合判断**：`PortfolioManager` 接收所有报告和证据，保留分歧、不确定性及待核验条件。
6. **可观测性**：`AgentTrace` 保存实际输入、输出与耗时；当前未做数据库持久化，也未记录失败轨迹。
7. **可替换设计**：网关隔离模型供应商，角色接口便于后续接入带工具的 Agent，向量存储接口隔离 Qdrant。

这一步实现的是可测试的多角色协作基础。自主工具选择、Graph 状态管理、多轮辩论、长期记忆、上下文压缩与评测需要各自独立实现和验证。

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

下一阶段接入真实 A 股行情、新闻等数据；同时保留 P6/P7 中尚未完成的会话持久化、HTTP 超时、阶段预算和上下文压缩任务。前端沿用已确定的浅色聊天交互设计。
