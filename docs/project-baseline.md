# 项目当前基线

更新：2026-10-31。用于跨会话保持用户已确认的方向；设计原型不计为正式应用已实现。

## 已确认的目标与约束

- 项目定位为**常规 Agent 学习项目**。用户撤回“企业级项目”要求，不按企业平台范围实施。
- Java 技术栈，采用 Spring AI Alibaba Agent/Graph；A 股优先，预留美股边界。
- 以 TradingAgents 核心研究机制为参考，用聊天交互串起学习。按阶段理解机制并独立实现，不逐文件翻译 Python。
- 首版 RAG 使用 Qdrant；SimpleVectorStore、Elasticsearch、PostgreSQL+pgvector 保留为同一 RAG 模块中的后续 adapter/profile。
- 运行时按 Spring Boot/Alibaba 框架最低要求选择 JDK；自己的源码、测试和教学示例只使用 Java 8 风格语法，不使用 record、sealed interface、var、模式匹配、getFirst/getLast 等较新的语言/API写法。
- 代码规范是长期约束：每个类、类属性、公开/关键方法和关键算法步骤都必须有清晰中文注释；注释说明职责、输入输出、状态变化、边界和为什么这样设计。
- 代码注重可扩展设计：优先使用接口、策略模式、工厂模式、适配器模式、模板方法和组合；领域层不依赖具体模型、向量库、数据源 SDK；新增 provider/存储后端尽量通过新增实现类和配置完成，不修改核心编排。
- 保留原基金学习工程，不把旧手写循环再套到 Alibaba Agent 外。

## 首版 UI 与交互：v6 已确认

用户确认当前浅色版本，后续首版前端按此实现，不再以旧图作为默认基线。

- [可点击原型](../deliverables/ui-v6/index.html)
- [Java 代码风格与扩展约束](code-style.md)
- [研究对话实拍](../deliverables/ui-v6/research-workspace-v6.png)
- [新建研究实拍](../deliverables/ui-v6/new-research-v6.png)
- [证据抽屉实拍](../deliverables/ui-v6/evidence-drawer-v6.png)

视觉：白底、冰蓝侧栏 #F2FAFF、天蓝主色 #229FDF、发送按钮 #168FCE、薄荷青 #35C3B1、正文蓝灰 #34495B。延续系统中文黑体与舒展的阅读间距。

交互：两栏导航与聊天；结论、图表、来源在对话中；研究过程折叠；报告、角色观点、风险和证据按需展开；追问沿用当前股票与研究日期。完整报告可以单独阅读。临时抽屉不变成固定第三栏。文档 RAG 只增加附件入口、资料范围小面板、索引状态和证据抽屉，不改变 UI v6 的视觉基线。

原型中的演示行情、研究结论与假来源不直接进入真实功能。正式版本图表使用数据工具结果，引用需能回到实际证据。界面不自动执行交易。

## 简化的工程范围

默认单用户、本地运行、一个前端和一个由 Maven 多模块组成的 Spring Boot 后端。业务数据库使用 MySQL；RAG 只作为一个 `stock-agent-rag` Maven 模块，首版使用 Qdrant，SimpleVectorStore、Elasticsearch、PostgreSQL+pgvector 作为后续 adapter/profile。具体依赖与框架兼容性先做验证，不要求一次启动所有后端。

保留工具校验、基本预算、超时、有限重试、取消、来源、必要检查点和简单测试，目的是理解 Agent 的执行边界。

不默认建设多租户、组织权限、分布式队列、多 worker 租约/fencing、高可用、Kubernetes、计费、复杂审计平台或生产 SLA。完整组合管理、定时监控、交易撮合和资金回测不是当前学习主线的必做功能。

## 文档优先级与当前状态

当前方向以本文件和[学习路线](../learning/curriculum.md)为准。`deliverables/2026-10-31-java-equity-agent/` 中原 R0–R6、Task 1–13/H1、M01–M14 的广范围方案保留为历史参考，不作为本轮首版必做清单。本文不声称已经逐条重写那些历史文档。

UI v6 与普通学习项目定位已获用户确认；下列 L0–L7 是据此整理的学习阶段建议，尚未实施。产品代码、课程正文与完整模型联调均未因本次记录而完成。


## 当前设计草稿

已整理[整体设计](superpowers/specs/2026-10-31-stock-agent-learning-design.md)与[文档 RAG 设计](superpowers/specs/2026-10-31-document-rag-design.md)。已确认的 UI v6 与常规学习项目定位保持；设计细节待审阅，不计为产品实现完成。

RAG 使用内置可分发样例和用户上传，共用解析/索引/检索与引用链；官方真实财报可由用户选用导入，自动采集后置。业务数据库改为 MySQL；RAG 通过适配器支持 SimpleVectorStore、Qdrant、Elasticsearch、PostgreSQL+pgvector。
