# P8：从 Java 执行器到显式研究 Graph

这一步把同一组分析师与组合经理接入两种可替换工作流。默认 `JAVA_EXECUTOR` 保留原行为，显式传 `ALIBABA_GRAPH` 才使用 Spring AI Alibaba 1.1.2.2 的真实 `StateGraph`。`engine` 选择框架，`mode=SERIAL|PARALLEL` 决定独立角色如何调度，二者不是同一个概念。

## 先运行同一请求，再读代码

应用仍使用现有浅色聊天界面，本阶段没有新增页面或更换默认引擎。学习对照通过API选择引擎。

```bash
# SAMPLE模式支持这组固定证券/日期，不表示实际行情。
curl http://localhost:8080/api/research/chat/market \
  -H 'Content-Type: application/json' \
  -d '{"question":"说明指标与证据缺口，不给买卖建议", "symbol":"600519", "asOf":"2026-10-30", "mode":"PARALLEL", "engine":"ALIBABA_GRAPH"}'
```

把 `engine` 改为 `JAVA_EXECUTOR`，比较相同角色输入与结果契约；把 `mode` 改为 `SERIAL`，观察图的串行边。真实模型输出可能不同，不应以逐字相等判断两个引擎等价。确定性测试使用相同脚本网关验证提示词和证据契约一致。

后台 `/api/research/tasks` 同样接受 `engine`，请求示例另加 `scope=MARKET`。创建任务后按原API查询、取消和恢复历史；新请求JSON和结果都保留实际引擎，旧归档不重放。资料问答当前仍是单分析师流程，传 `scope=DOCUMENTS` 与 `engine=ALIBABA_GRAPH` 会返回400。

## 实际节点与状态

```mermaid
flowchart TD
    Start([START]) --> Prepare[prepare：校验当前状态]
    Prepare --> Fundamental[analyst_FUNDAMENTALS]
    Prepare --> Technical[analyst_TECHNICAL]
    Prepare --> News[analyst_NEWS]
    Prepare --> Sentiment[analyst_SENTIMENT]
    Prepare --> Risk[analyst_RISK]
    Fundamental --> Manager[manager：校验完整报告并汇总一次]
    Technical --> Manager
    News --> Manager
    Sentiment --> Manager
    Risk --> Manager
    Manager --> End([END])
```

串行模式把五个角色节点连成单链，但各角色仍只读取相同事实与证据，不会自动变成多空辩论。并行模式使用Graph的扇出/汇聚与ALL_OF聚合，配置本次请求拥有的最多8线程执行器。

图中实际保存 `question`、`market`、`evidence`、`prepared`、`report_<ROLE>`、`decision` 和可选 `managerTrace`。状态只由基础Map/List/String/Number构成，避免把模型、JDBC连接、BudgetLedger、回调或不可反序列化领域DTO放进状态。`ResearchGraphState`负责领域对象与这些基础类型之间的适配。

每个分析师写独立报告键，使用Replace策略；因此不会由多个并行角色共同覆盖一个可变reports列表。经理从图状态重建并校验所有已注册角色的报告，再按稳定角色顺序汇总。响应的 `workflowState` 只返回实际状态键、已完成角色和是否产生决策，完整正文继续位于 `decision`、`traces`，避免重复输出大段证据。

## Harness 与 Graph 各自负责什么

Graph负责节点路由、状态合并、汇聚和执行顺序。Harness负责调用次数/估算Token原子预留、阶段与全局截止、模型HTTP截止与取消。使用框架不会自动得到后者。

两种引擎共用120秒全局上限、60000估算Token和按角色数设置的调用上限；分析师子阶段45000估算Token，经理15000。经理30秒时限在进入经理阶段时创建，避免等待分析师时就耗尽它的时间。

任意图角色失败会设置本次请求共享停止信号，让其余角色的Harness中断等待并取消模型工作线程；外层也有全局截止。保留首个真实角色错误，避免被兄弟节点随后产生的取消异常覆盖。用户取消、失败和超时都会关闭本次请求创建的执行器；无法强制终止完全忽略中断的第三方代码，因此模型传输仍必须有HTTP超时。

已有工作台仍独立归档实际进度与已完成角色轨迹。Graph没有替代MySQL事务或任务终态规则；任务取消后迟到结果仍不可发布。

## 对应的 Agent 学习点

|问题|知识点|代码入口|
|---|---|---|
|如何更换编排方式而不重写角色？|策略、组合与依赖反转|`ResearchWorkflow`、`MultiResearchService`|
|为什么不能把所有对象都塞进state？|显式状态、序列化边界与上下文工程|`ResearchGraphState`|
|并行更新为何可能覆盖？|每键合并策略、独立状态槽|`GraphResearchWorkflow`中的KeyStrategy|
|何时才能进入汇总？|节点边、扇出/汇聚、完整性校验|图的manager节点|
|框架能否替代预算与取消？|Graph与Harness职责分离|`WorkflowSupport`、`DeadlineExecutor`|
|如何判断确实走了图？|可观测性与真实框架测试|`engine`、`workflowState`、Graph单测|
|如何证明不是串行伪装并行？|同步屏障验证并发重叠|`GraphResearchWorkflowTest`|
|失败后如何防止继续收费？|共享停止信号、模型前预算与取消检查|Graph角色节点与Harness|

## 测试与复现

```bash
# 无密钥：真实Graph、脚本网关、状态汇聚/隔离、并发/预算/超时/取消、真实HTTP和归档
mvn -pl stock-agent-web -am test \
  -Dtest=GraphResearchWorkflowTest,GraphResearchHttpTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 真实模型，显式启用；从本地安全环境加载配置后运行
GRAPH_RESEARCH_INTEGRATION=true STOCK_AGENT_CHAT_THINKING_MODE=disabled \
  mvn -pl stock-agent-web -am test -Dtest=GraphResearchLiveIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dstock.eval.report="$PWD/evaluation-reports"
```

真实验收只使用固定SAMPLE数据，实际调用五位分析师和一次经理；不写Qdrant业务索引、不执行交易。`thinking`扩展仅适用于供应商支持的情况。报告写入被忽略的 `evaluation-reports/p8-graph-live.json`。

## 本轮边界

这是单次研究内的显式图状态，不是跨进程检查点恢复。`checkpointed=false` 表示未交付持久Saver；工作台重启仍把未完成任务标为INTERRUPTED，不自动续跑或重新调用模型。多空辩论、条件重跑、持久检查点和人类审批节点可以作为后续独立练习。

本轮使用的是Graph编排普通Java分析师；它不声称每个分析师都已拥有ReactAgent自主工具循环。P11的真实ReactAgent工具选择是另一个受控评测案例。框架接口以[Alibaba v1.1.2.2 StateGraph源码](https://github.com/alibaba/spring-ai-alibaba/blob/v1.1.2.2/spring-ai-alibaba-graph-core/src/main/java/com/alibaba/cloud/ai/graph/StateGraph.java)及本地锁定依赖为准。
