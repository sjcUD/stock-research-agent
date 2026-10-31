# 项目任务清单

> 每次完成任务后同步更新本文件，并在回复中说明：已完成、验证结果、下一步任务。

## 当前状态

- 当前阶段：聊天研究 Agent 接入前的基础链路已完成
- 当前主链路：文档解析 → Embedding → Qdrant → Citation → RAG API
- 当前测试：20 个测试用例；外部服务测试默认通过环境变量启用
- 当前主分支：`main`

## 已完成

### 项目基础

- [x] 建立 Maven 多模块项目骨架
- [x] 固定 Java 8 风格源码约束
- [x] 建立 LLM、Embedding、Rerank 网关与模型注册表
- [x] 增加 Spring AI Alibaba ReactAgent 最小工厂
- [x] 增加工具调用闭环探针和基本面分析师
- [x] 清理不应提交的本地研究资料目录
- [x] 重写 README 并推送到 GitHub

### 数据与 RAG

- [x] 增加 SAMPLE 数据模式和样例行情提供器
- [x] 建立 RAG 核心契约、文档切片和检索模型
- [x] 增加 PDF、Word、Excel、HTML、纯文本和 Markdown 解析
- [x] 完成解析、切片和向量入库编排
- [x] 接入 Spring AI EmbeddingModel
- [x] 使用 DashScope `text-embedding-v4` 完成真实 API 联调
- [x] 增加 Qdrant VectorStore 适配器
- [x] 验证真实 Qdrant 写入、检索和引用回传
- [x] 完成真实 Embedding → Qdrant → 检索 → Citation 端到端联调

### 运行记录与 API

- [x] 增加 Harness 的预算、失败分类和上下文压缩基础实现
- [x] 增加 MySQL 初始迁移脚本
- [x] 增加 MySQL JDBC Repository 与研究运行记录
- [x] 完成 MySQL 容器联调并验证 JDBC 账号配置
- [x] 增加研究运行 Web API
- [x] 增加 RAG 文档入库和检索 Web API
- [x] 将聊天研究 Agent 接入 RAG 检索证据
- [x] 建立 JUnit 测试，当前 20 个测试通过

## 当前待完成

### P6：真实 ChatModel 与聊天闭环

- [ ] 接入真实 ChatModel provider
  - [x] 增加 OpenAI 兼容 ChatModel 配置
  - [ ] 支持模型、Base URL、API Key 和超时配置
  - [x] 增加真实模型联调测试
- [ ] 装配 `ResearchChatService` 到 Web 容器
  - [x] 将真实 ChatModel 注入 `FundamentalsAnalyst`
  - [x] 启用 `/api/research/chat` 的条件装配
  - [x] 验证“问题 → RAG → 模型回答 → Citation”
- [ ] 增加聊天会话和消息持久化
  - [ ] conversation 表 Repository
  - [ ] 消息列表和运行状态查询
  - [ ] 失败状态和错误原因回传

### P7：Harness 完整运行时

- [ ] 完善模型调用预算
  - [x] 请求次数预算
  - [ ] Token 预算
  - [ ] 单阶段和全局预算
- [ ] 增加超时和取消
  - [x] 模型调用截止时间检查
  - [x] 调用前取消检查
  - [ ] Qdrant 检索超时
  - [ ] 用户取消后的资源释放
- [ ] 增加有限重试和错误恢复
  - [x] 429、5xx、网络错误分类
  - [x] 有限重试和退避
  - [ ] 不可重试错误直接返回
- [ ] 接入上下文压缩策略
  - [ ] 保留用户问题和关键事实
  - [ ] 压缩历史消息和低分证据
  - [ ] 记录压缩前后 token 估算

### P8：多分析师协作

- [ ] 增加技术面分析师
- [ ] 增加新闻分析师
- [ ] 增加情绪分析师
- [ ] 增加风险分析师
- [ ] 设计分析师结果统一契约
- [ ] 实现分析师串行编排
- [ ] 实现分析师并行编排
- [ ] 增加组合经理/决策汇总角色
- [ ] 记录每个 Agent 的输入、输出、证据和耗时

### P9：行情数据与外部数据源

- [ ] 确定首版 A 股真实行情数据源
- [ ] 实现 LIVE 数据提供器
- [ ] 增加数据源超时、限流和降级
- [ ] 保留数据日期、来源和版本
- [ ] 增加新闻和公告数据源
- [ ] 增加 SAMPLE/LIVE 切换配置
- [ ] 为关键行情字段增加数据质量校验

### P10：聊天式研究前端

- [ ] 实现聊天消息列表
- [ ] 实现研究任务状态展示
- [ ] 实现 Agent 阶段进度展示
- [ ] 实现证据引用抽屉
- [ ] 实现文档上传和解析状态
- [ ] 实现 Markdown、表格和代码块渲染
- [ ] 实现错误、取消和重试交互
- [ ] 接入 `/api/research/chat`、`/api/rag/documents`、`/api/rag/search`

### P11：评测与质量验证

- [ ] 建立固定 RAG 评测数据集
- [ ] 评测召回率、命中率和 Citation 完整性
- [ ] 评测 Embedding 模型和维度变化
- [ ] 评测不同 chunk size 和 topK
- [ ] 增加聊天回答离线评测
- [ ] 增加工具调用准确性评测
- [ ] 增加预算、超时和重试场景测试
- [ ] 增加 Qdrant、MySQL 和模型服务集成测试

### P12：运行与部署

- [ ] 增加 Docker Compose：Qdrant、MySQL、应用
- [ ] 增加 application-local 配置模板
- [ ] 增加健康检查和依赖检查
- [ ] 增加启动、停止和清理脚本
- [ ] 增加日志脱敏检查
- [ ] 增加 README 本地完整启动说明

## 待确认事项

- [ ] 首版 ChatModel provider 和模型名称
- [ ] 首版 A 股行情数据源
- [ ] 是否默认启用 Qdrant RAG
- [ ] 聊天接口是否需要流式输出
- [ ] 前端优先实现桌面端还是移动端

## 每次任务完成后的汇报格式

```text
已完成：
- ...

验证结果：
- ...

下一步：
- ...
```
