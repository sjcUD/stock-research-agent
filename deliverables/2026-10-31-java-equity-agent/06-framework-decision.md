# 框架决策：Spring AI Alibaba Agent/Graph

日期：2026-10-31。**状态：用户已确定主框架；兼容验证和产品实现尚未执行。**

## 决策

股票产品在`/Users/sjc/Documents/stock/`独立实现，采用Spring AI Alibaba Agent Framework与Graph。Spring AI负责底层模型/工具/Embedding/MCP，ReactAgent负责受控Agent循环，Graph负责研究工作流、路由、并行及检查点/中断。Java业务服务负责数据、证据、权限、父预算、操作票据、任务/事件与产物。

不在同一产品中再引入LangGraph4j；原手写基金项目保留。Alibaba框架不要求把模型统一换成通义千问，模型适配仍需按选定版本与配置验证。

## 版本验证基线

首个R0候选：JDK21、Spring Boot3.5.8、Spring AI Alibaba1.1.2.2、Spring AI1.1.2。后三者来自[固定发布tag的POM](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.2/pom.xml#L79-L92)；不得称为本项目已测试通过，也不混装原工程的Spring AI1.1.8。根据R0验证结果再写最终依赖锁与具体API依据。

## R0通过条件

1. ReactAgent从工具意图到结果回填可复现，普通Java类schema正确，不调用旧手写ReAct循环。
2. Graph条件分支与并行合并正确，独立角色上下文不串用，maxConcurrency=2有效。
3. 模型/工具拦截能执行共享父预算、重试计数、deadline与取消，不能只限制最外层调用。
4. 检查点序列化后可跨JVM重启读取；人工中断与恢复可定位同一图状态，旧参数确认与不一致指纹被拒。
5. 框架事件可转换为产品runId/toolCallId/sequence协议；事件重连与图恢复分别验收。

框架中断/检查点不自动保证业务副作用只执行一次；确认票据和数据库CAS仍需产品验证。若能力或版本不满足，记录可复现证据并修订版本方案，不能静默换主框架。

## 对方案与学习路线的影响

- R0任务1增加ReactAgent、Graph分支/并行、持久化与中断探针。
- R1任务4用Graph构建研究阶段，ResearchWorkflow作为调用入口而非另一套阶段引擎。
- R2任务7把动作确认接到图中断/恢复，保留精确参数授权与幂等消费。
- R3任务8用Alibaba角色Agent与并行组合，保留结构化RoleReport、证据校验和父预算。
- M01/M03/M04/M07/M08/M09明确对应框架知识，其他业务模块通过这些机制完成任务。

本次仅确认并同步架构选择；用户没有因此批准未完成的版本验证、全部产品设计或代码实现。UI方向与功能里程碑保持不变。
