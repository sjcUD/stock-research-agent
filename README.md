# Stock Research Agent

一个面向学习的 Java 股票研究 Agent 项目。

项目以 A 股研究为背景，使用 Spring AI Alibaba Agent Framework 和 Spring AI Qdrant Store，逐步实现“数据获取 → 工具调用 → 分析师 → RAG 检索 → 研究结论”的完整链路。代码优先保持 Java 8 风格，重点展示 Agent 的基本组成和可扩展设计，不追求企业级平台的复杂运维能力。

## 当前状态

当前仓库是可编译、可测试的 Maven 多模块骨架，已经包含：

- Spring AI Alibaba `ReactAgent` 的最小 Agent 工厂
- 一个可重复运行的工具调用闭环探针
- 基本面分析师的模型网关抽象
- LLM、Embedding、Rerank 的统一接口和模型注册表
- SAMPLE 数据模式和固定行情提供器
- RAG 文档切片、检索契约与 Qdrant VectorStore 适配器
- Agent Harness 的预算、失败分类和上下文压缩基础实现
- MySQL 初始迁移脚本

真实行情、真实模型、MySQL、Qdrant 和 Web 端尚未接通，当前实现用于学习和验证模块边界。

当前任务进度见 [TODO.md](TODO.md)，每次完成任务都会同步更新已完成项和下一步。

## 学习目标

按下面的顺序阅读和运行，可以从简单闭环逐步理解 Agent：

1. **模型调用**：理解 ChatModel、Embedding、Rerank 为什么需要独立网关。
2. **工具调用**：观察 Agent 如何决定调用工具、接收工具结果并生成回答。
3. **数据接入**：区分 SAMPLE 与 LIVE 数据，理解来源、时间和可追溯性。
4. **单分析师**：把行情快照转换成结构化提示词，再交给模型分析。
5. **RAG**：学习文档解析、切片、向量化、检索和引用信息如何衔接。
6. **Harness**：学习预算、超时、失败分类、有限重试和上下文压缩。
7. **多 Agent**：在单分析师稳定后，再扩展技术面、新闻、情绪和风控角色。

## 模块结构

```text
stock-agent-learning/
├── stock-agent-contracts/          # 跨模块公共契约
├── stock-agent-data-contracts/     # 数据模式等领域枚举和契约
├── stock-agent-llm/                # Chat/Embedding/Rerank 网关与模型注册
├── stock-agent-rag/                # RAG 核心契约、切片、检索和 Qdrant 适配器
├── stock-agent-data-provider/      # SAMPLE/LIVE 数据提供器边界
├── stock-agent-research-agent/     # Agent、工具循环和分析师
├── stock-agent-runtime/            # Harness、预算、失败处理、上下文策略
└── stock-agent-web/                # Web/API 接入预留模块
```

模块之间通过接口协作，供应商 SDK 尽量隔离在适配层中。后续可以在同一个 `stock-agent-rag` 模块中增加其他 VectorStore 实现，而不改变上层检索接口。

## 一次研究流程

```text
用户问题
   ↓
研究 Agent
   ↓
读取股票数据 / 检索文档
   ↓
工具结果与引用证据
   ↓
基本面分析师
   ↓
研究结论
```

运行时的 Harness 负责为模型调用预留预算，并对错误进行分类；上下文策略负责在输入过长时保留关键内容并压缩其余上下文。

## 技术栈

- Java 8 风格源码
- Maven 多模块
- Spring AI Alibaba Agent Framework `1.1.2.2`
- Spring AI Qdrant Store `1.1.2`
- MySQL（业务数据，迁移脚本已提供）
- Qdrant（首版向量存储）
- JUnit 5

源码不使用 `record`、`sealed interface`、`var`、模式匹配或 `getFirst/getLast` 等较新的 Java 语法，便于按 Java 8 的方式阅读对象、接口和集合代码。

## 快速开始

### 环境要求

- JDK 17+（当前 Spring AI 版本的运行要求）；源码保持 Java 8 风格
- Maven 3.8+
- 真实模型和 Qdrant 联调时，再配置对应服务地址和密钥

### 编译与测试

```bash
cd /Users/sjc/Documents/stock
mvn test
```

当前测试覆盖工具闭环、模型配置、数据提供器、RAG 切片、Qdrant 配置校验和 Harness 基础策略。

### 运行说明

当前 `stock-agent-web` 还是预留模块，仓库暂未提供可直接访问的 Web 服务。建议先从以下类开始阅读：

- `stock-agent-research-agent/.../AgentToolLoopProbe.java`
- `stock-agent-research-agent/.../analyst/FundamentalsAnalyst.java`
- `stock-agent-research-agent/.../analyst/SingleAnalystGraphFactory.java`
- `stock-agent-llm/.../ModelProfile.java`
- `stock-agent-rag/.../store/qdrant/QdrantVectorStore.java`
- `stock-agent-runtime/.../harness/BudgetLedger.java`
- `stock-agent-runtime/.../harness/ContextPolicy.java`

## 代码约定

- 类、字段、公开方法和关键算法必须有清晰的中文注释。
- 优先使用接口、策略、工厂、适配器和组合，避免把第三方 SDK 传播到核心领域代码。
- 数据来源、时间、检索分数和引用信息要能够随结果一起传递。
- 先完成可测试的最小闭环，再逐步增加真实数据、真实模型和多 Agent 编排。
- 本地凭据、构建产物、研究草稿和设计交付物不纳入版本库。

## 后续路线

1. 接通 Qdrant 文档写入和检索流程。
2. 增加 MySQL Repository 与研究运行记录。
3. 接入一个真实的 ChatModel 和 Embedding 模型。
4. 完成技术面、新闻、情绪分析师及组合决策流程。
5. 补充 Web API 和聊天式研究界面。
6. 增加离线评测，比较检索质量、工具调用和上下文压缩效果。

这是一个循序渐进的学习项目。每一步都应先保留一个能够独立运行和测试的最小例子，再向下一层抽象扩展。
