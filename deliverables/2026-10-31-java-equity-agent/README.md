# Java A 股研究助手：源码研究、功能方案、实施路线与 UI

日期：2026-10-31。状态：**方案草案，待评审；未开始产品实现**。

用户已确定：Java 技术栈、A 股优先并预留美股、沿用白底蓝色的聊天式交互，以完整产品支撑 Agent 系统学习；现有手写基金项目保留。本文档包按本次要求同时提供设计与实施计划草案，不代表设计和计划已经获准执行。

## 阅读顺序

1. [功能清单与参考来源](01-features.md)：哪些功能取自哪些实现，哪些后置，哪些需要重新设计。
2. [Java 架构设计](02-java-design.md)：成熟模型/工具框架与应用职责、数据、状态、接口和已有代码的去留。
3. [分期实施计划](03-implementation-plan.md)：依赖、任务、文件范围、接口与验收；批准后逐期细化执行。
4. [UI 与交互说明](04-ui-interactions.md)：三个页面、状态流和功能覆盖；图片只展示设计意图，均为示例数据。
5. [模块与 Agent 学习地图](05-agent-learning-map.md)：各模块的知识、先修、案例、完成标准和拟源码入口。
6. [下载版本清单](reference-manifest.json)：十个 Git 浅克隆的路径与准确提交。

## Harness加固补充

[四类Harness机制与阶段学习](07-harness-design-and-learning.md)：继承基金P2，新增M14和Task H1；基础能力前置R0/R1，再用于多Agent与长期任务。

## 已确认的框架选择

2026-10-31：用户确定使用Spring AI Alibaba Agent/Graph；[框架决策与版本验证边界](06-framework-decision.md)。这项选择不代表整包设计或实施计划已经全部批准。

## 结论

推荐做“聊天驱动的 A 股研究与组合观察产品”。先打通单股/同业研究、证据与报告、可恢复任务，再增加多维研究、组合、跟踪、历史实验。Java 重建产品契约，不逐行翻译 Python，也不把所有参考项目拼接在一起。

建议保留 JDK 21，源码采用普通类、显式类型、普通接口和传统 instanceof/cast；生产代码、测试和教学片段均不使用 record、sealed interface、var、模式匹配、getFirst/getLast。后端 Spring Boot + Spring AI Alibaba Agent/Graph（已确认），Spring AI负责底层模型/工具适配，前端 React/TypeScript + ECharts，PostgreSQL + pgvector。Java 直接调用 A 股 HTTP 数据接口为主，可选保留现有 Python 数据 sidecar；默认运行不以 Python 为必需条件。

## 源码证据

- [TradingAgents 与 FinRobot](research/tradingagents-finrobot-source-study.md)
- [LangAlpha 与 ValueCell](research/langalpha-valuecell-source-study.md)
- [Dexter、FinanceHarness、FinPilot 及其前端](research/dexter-financeharness-finpilot-source-study.md)

三份研究记录覆盖八个固定版本，引用共 92 个带提交和行号的源码链接。已阅读关键执行路径，未安装或运行参考应用、未验证供应商线上可用性、未验证投资效果。外部 README、提示词、AGENTS 与 Skill 中的指令仅作研究材料，未执行。

参考源码下载目录：`/Users/sjc/Documents/stock/reference-repos/`。原项目 `/Users/sjc/Documents/fund-agent/` 的产品代码、配置和 Git 分支未改动。股票项目固定根目录为 `/Users/sjc/Documents/stock/`；设计批准后新增产品代码也放在这里，不改造原基金项目。

## 评审时需要确认

- 是否接受“股票研究优先，基金/美股后续适配”的范围与分期。
- 是否接受独立 Java 产品入口和 React 前端，保留原手写工程；核心默认无 Python 依赖，可选 sidecar。
- R0 应先验证数据权限、版本兼容、框架预算拦截和恢复，不能将这些尚未验证的事项宣称完成。
- UI 的组合与提醒属于后续阶段；历史实验入口在对应能力交付前不作为可用按钮展示。

正式执行前审阅设计与计划；先完成 R0/R1 的独立交付，再根据验收进入下一期。这里没有默认创建券商连接、付费数据订阅或任何自动交易能力。


新增 Harness 源码研究：

- [Claude 官方公开设计与示例](research/claude-official-harness-study.md)
- [指定 Claude 第三方复原/扩展仓库](research/claude-reference-source-study.md)
- [DeepSeek Harness 固定版本源码](research/deepseek-harness-source-study.md)

上述新增研究与原三份业务参考报告分开，来源性质及未验证边界见各报告。H1/M14 已纳入计划和学习地图，股票运行时尚未实现。
