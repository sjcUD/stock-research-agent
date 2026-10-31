# Java 股票 Agent 学习项目实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 按已确认设计构建 Java/Spring AI Alibaba 股票 Agent 学习项目，首版使用 MySQL、Qdrant、独立 LLM 管理和 UI v6。

**Architecture:** Maven 多模块单应用。MySQL 保存业务状态，stock-agent-rag 是单个 RAG 模块，内部用 package 和 adapter 支持 Qdrant 首版及其他后端；Spring AI Alibaba Agent/Graph 负责研究流程；前端按 UI v6 接入聊天、研究阶段、文档状态和引用。

**Tech Stack:** JDK 17+ runtime，源码使用 Java 8 风格语法；Spring Boot 3.5.8、Spring AI Alibaba 1.1.2.2、Spring AI 1.1.2、Maven、MySQL、Qdrant、React/TypeScript/Vite。

**Spec:** docs/superpowers/specs/2026-10-31-stock-agent-learning-design.md；docs/superpowers/specs/2026-10-31-document-rag-design.md

## Global Constraints

- 首版 RAG 使用 Qdrant；SimpleVectorStore、Elasticsearch、PostgreSQL+pgvector 为同一 RAG 模块内的后续 adapter/profile。
- 业务数据库使用 MySQL。
- 源码只使用 Java 8 风格，不使用 record、sealed interface、var、模式匹配、getFirst/getLast。
- 每个类、类属性、公开/关键方法和关键算法步骤写清楚中文注释。
- 优先接口、策略、工厂、适配器、模板方法和组合。
- 单用户、本地运行，不建设企业级分布式平台。
- UI v6 视觉基线保持，只补 RAG 文档交互。

## Review Focus

- Alibaba 固定版本真实 API 和 Qdrant 维度/profile mismatch。
- MySQL 业务状态与 RAG generation 激活的一致性。
- Java 8 风格和中文注释完整性。
- 预算、取消、超时、重试和上下文保护。
- citation 是否绑定正确的 document revision、page 和 chunk。

### Task 1: Maven 骨架与框架探针

Files:
- Create: pom.xml and eight module pom.xml files.
- Create: stock-agent-research-agent/src/main/java/.../StockAgentApplication.java
- Test: FrameworkCompatibilityTest.java

Interfaces:
- 一个 scripted ChatModel、一个只读 market tool、一次真实 Agent tool result 回填。
- 不依赖真实 API key、Qdrant 或 MySQL。

Steps:
- [ ] 写失败测试：stub model 请求 market tool，并断言 tool result 回填。
- [ ] 运行 mvn test，观察失败。
- [ ] 实现 parent/module POM 和最小 Spring Boot wiring，禁止新增手写 ReAct loop。
- [ ] 运行兼容性测试并记录真实 API 行为到 docs/learning/L0-framework-probe.md。
- [ ] 运行全模块测试。

### Task 2: LLM 管理与 MySQL 基础

Files:
- Create: stock-agent-llm ModelProfile、ProviderRegistry、ChatModelGateway、EmbeddingGateway、RerankGateway。
- Create: stock-agent-data-contracts DataMode。
- Create: stock-agent-runtime V1__baseline.sql。
- Test: profile validation and MySQL migration smoke tests。

Interfaces:
- ModelProfile 包含 provider、model、baseUrl、dimensions、batchSize、timeout、retry。
- Gateway 隐藏具体 SDK。
- MySQL 表包含 conversation、research_run、run_event、document、document_revision、index_generation。

Steps:
- [ ] 写 profile、SAMPLE/LIVE 和非法维度测试并观察失败。
- [ ] 实现普通 immutable class、构造器、getter 和中文注释。
- [ ] 添加 Flyway migration 与可选 Testcontainers smoke test。
- [ ] 运行目标测试与全套测试。

### Task 3: stock-agent-rag 与 Qdrant

Files:
- Modify: stock-agent-rag/pom.xml。
- Create: parser、chunking、embedding、retrieval、store、citation、application package。
- Create: QdrantVectorStore、DocumentIngestionService。
- Test: chunking、dimension mismatch、Qdrant contract tests。

Interfaces:
- VectorStore.upsert(List<DocumentChunk>, EmbeddingProfile)。
- VectorStore.search(RetrievalQuery)。
- DocumentChunk 保留 documentRevision、page、offset、textHash、metadata。
- Qdrant DTO 不泄漏到业务层。

Steps:
- [ ] 写切分边界、Embedding 指纹和维度不匹配测试。
- [ ] 实现文本/Markdown parser 与结构感知 chunking。
- [ ] 实现 Qdrant collection/filter/query adapter 和 generation 状态。
- [ ] 失败或取消时不激活半成品索引。
- [ ] 运行 unit tests；Qdrant integration test 使用 opt-in profile。
- [ ] 记录 L5 首条 RAG trace。

### Task 4: SAMPLE 数据源与单一基本面 Agent

Files:
- Create: DataProvider、ProviderCapability、SampleStockProvider、SampleFinancialProvider。
- Create: FundamentalsAnalyst、SingleAnalystGraphFactory。
- Test: sample source、future asOf、missing data、scripted analyst tests。

Interfaces:
- Provider 返回 source、asOf、unit、availability。
- Analyst 输出 typed RoleReport 和 citation。

Steps:
- [ ] 写 SAMPLE、未来日期和缺数据测试。
- [ ] 实现 deterministic sample provider 与只读 tools。
- [ ] 实现 Alibaba Agent/Graph 单角色链。
- [ ] 运行 scripted model tests。

### Task 5: Harness 基线

Files:
- Create: HarnessPolicy、BudgetLedger、DeadlineController、FailureClassifier、ContextPolicy。
- Test: quota race、retry、deadline、cancel、protected context tests。

Interfaces:
- 一个父 ledger 覆盖 model、tool、retry、embedding、summary。
- reserve、settle、remainingMillis、classify、prepareContext 为普通接口方法。

Steps:
- [ ] 写失败测试。
- [ ] 实现线程安全 reserve/settle 与单调时间。
- [ ] 实现有限恢复、完整 tool group 裁剪和保护字段。
- [ ] 运行目标测试与全套测试。

### Task 6: UI v6 与 RAG 交互

Files:
- Create/Modify: ResearchController、ResearchWorkspace.tsx、DocumentPicker.tsx、CitationDrawer.tsx、RunStageList.tsx。
- Test: upload status、selected document scope、citation drawer、SSE contract tests。

Interfaces:
- 保持 UI v6 两栏布局。
- 增加附件、资料范围、索引状态、引用抽屉和页码阅读。
- SSE 使用 runId/sequence，前端不伪造完成状态。

Steps:
- [ ] 写 API/component tests。
- [ ] 实现 DTO/controller。
- [ ] 实现 UI v6 交互补充。
- [ ] 运行 frontend tests 和 browser smoke check。

### Task 7: TradingAgents 四角色链

Files:
- Create: MarketAnalyst、NewsAnalyst、SentimentAnalyst。
- Create: DebateGraphFactory、RiskDiscussionGraphFactory。
- Test: scripted end-to-end and partial-failure tests。

Interfaces:
- 每个角色私有上下文、typed RoleReport、固定有界轮次。
- Qdrant RAG 作为基本面/新闻的可选证据工具。
- 所有角色共享父 BudgetLedger。

Steps:
- [ ] 写 scripted workflow tests。
- [ ] 实现并行 analyst fan-out/fan-in。
- [ ] 实现 Bull/Bear、Research Manager、Trader、三方 risk 和 final manager。
- [ ] 验证共享预算、部分失败和结构化 fallback。

### Task 8: 学习材料与评测

Files:
- Create: docs/learning/L0-L7/*.md、eval/cases/*.json、EvaluationRunner.java。
- Modify: learning/progress.md。

Steps:
- [ ] 每个已交付阶段写机制说明、成功 trace、失败 trace。
- [ ] 增加 single/multi-agent、no/with-RAG、no/with-memory cases。
- [ ] 增加 deterministic data/citation/tool/budget checks。
- [ ] 运行可复现评测并记录结果。

