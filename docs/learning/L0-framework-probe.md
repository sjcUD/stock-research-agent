# L0 框架兼容探针

日期：2026-10-31。

固定依赖：

- Spring AI Alibaba Agent Framework 1.1.2.2
- Spring AI 依赖由 Agent Framework 传递管理
- Maven 编译 release 8，运行时使用当前 JDK 21

本次实际验证：

- Maven 能解析并加载 com.alibaba.cloud.ai.graph.agent.ReactAgent
- ReactAgent 提供 builder、call、invoke、stream、interrupt、getThreadState
- Agent Framework jar 中存在 ModelInterceptor、ToolInterceptor 以及工具错误、工具重试、上下文编辑等拦截器扩展点
- 纯 Java 8 风格的最小工具闭环测试通过：模型动作意图 → 工具执行 → 工具结果回填 → 最终回答

当前探针仍使用固定样例闭环，尚未把真实 ChatModel、Qdrant、MySQL 接入研究流程。下一步在 LLM 管理模块中用 Gateway 隔离真实模型，避免业务代码直接依赖 provider SDK。
