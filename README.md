# 股票 Agent 学习项目

项目目录：`/Users/sjc/Documents/stock/`。

**当前定位：常规、本地运行的 Agent 学习项目。用户已撤回企业级项目要求。**采用 Java + Spring AI Alibaba Agent/Graph，A 股优先，参考 TradingAgents 核心研究机制，通过聊天完成研究与追问。

**首版界面与交互已确定采用 UI v6：白底、浅天蓝、薄荷青、两栏聊天布局。**正式应用尚未实施，现有 HTML 是可点击设计原型，非真实行情或模型分析。

## 当前入口

- [已确认的项目与 UI 基线](docs/project-baseline.md)
- [Java 代码风格与扩展约束](docs/code-style.md)
- [首版可点击 UI v6](deliverables/ui-v6/index.html)
- [UI 基线记录与快照](docs/ui-baseline.json)
- [L0–L7 系统学习路线](learning/curriculum.md)
- [学习与实现进度](learning/progress.md)
- [TradingAgents 深入源码研究](research/tradingagents-deep-study/README.md)
- [FinInsRAG-V3 RAG 源码研究](research/fininsrag-v3-rag-study.md)
- [已选主框架与待验证版本](deliverables/2026-10-31-java-equity-agent/06-framework-decision.md)

学习路线从模型/工具闭环、可信数据、聊天任务，逐步到四类分析师、讨论与风控、RAG/上下文、记忆/确认和评测。基础调用上限、超时、有限重试、取消与来源保留；不默认增加多租户、分布式任务、高可用、计费或复杂运维。

## 当前设计草稿

2026-10-31：设计已整理，待用户审阅，尚未进入实现。

- [整体学习项目设计](docs/superpowers/specs/2026-10-31-stock-agent-learning-design.md)
- [内置样例与上传文档 RAG 设计](docs/superpowers/specs/2026-10-31-document-rag-design.md)
- [Maven 多模块实施计划](docs/superpowers/plans/2026-10-31-stock-agent-implementation.md)

新设计采用 Maven 多模块单应用、MySQL 业务数据库与可替换 RAG 后端；首版使用 Qdrant，其他后端保留在同一个 RAG 模块的 adapter/profile 中。具体版本兼容性和检索效果仍待阶段验证。UI v6 已确认，未被本轮改画或覆盖。

## 历史方案与参考

[原完整研究平台方案](deliverables/2026-10-31-java-equity-agent/README.md)包含旧 R0–R6、Task 1–13/H1、M01–M14，用作机制与架构参考，不作为当前首版必做清单。新学习阶段使用 L0–L7，避免与原基金 P0–P7 混淆。

- [四类 Harness 机制与源码借鉴](deliverables/2026-10-31-java-equity-agent/07-harness-design-and-learning.md)
- [参考源码版本](deliverables/2026-10-31-java-equity-agent/reference-manifest.json)

`reference-repos/` 的十份独立浅克隆仅用于源码研究，不纳入本项目版本管理。原基金项目保留在 `/Users/sjc/Documents/fund-agent/`。参考实现、学习设计、UI 原型与产品代码分别记录，不把前者计为后者已完成。
