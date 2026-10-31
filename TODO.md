# 项目任务清单

> 维护规则：每次完成一项任务，都要同步更新本文件，并在回复中说明“已完成”和“接下来要完成”。

## 已完成

- [x] 建立 Maven 多模块项目骨架
- [x] 固定 Java 8 风格源码约束
- [x] 建立 LLM、Embedding、Rerank 网关与模型注册表
- [x] 增加 SAMPLE 数据模式和样例行情提供器
- [x] 增加 Spring AI Alibaba ReactAgent 最小工厂
- [x] 增加工具调用闭环探针和基本面分析师
- [x] 建立 RAG 核心契约、文档切片和检索模型
- [x] 增加 Qdrant VectorStore 适配器
- [x] 增加 Harness 的预算、失败分类和上下文压缩基础实现
- [x] 增加 MySQL 初始迁移脚本
- [x] 建立 JUnit 测试，当前 18 个测试通过
- [x] 清理不应提交的本地研究资料目录
- [x] 重写 README 并推送到 GitHub

## 下一步

- [x] 完成纯文本解析、切片和向量入库编排
- [x] 增加基于 Apache Tika 的 PDF、Word、Excel、HTML 解析适配器
- [ ] 接入真实 Embedding 模型并验证 Qdrant 写入
- [ ] 接通 Qdrant 检索、引用和来源回传
- [ ] 增加 MySQL Repository 与研究运行记录
- [ ] 接入真实 ChatModel 和 Embedding 配置
- [ ] 完善预算、超时、取消和有限重试链路
- [ ] 增加技术面、新闻、情绪分析师
- [ ] 实现分析师协作与组合决策流程
- [ ] 补充 Web API 和聊天式研究界面
- [ ] 增加离线评测和端到端集成测试

## 待确认

- [ ] 确定首版真实行情数据源
- [ ] 确定首版 ChatModel 与 Embedding 服务
- [ ] 确定 Qdrant 的本地运行方式和 collection 配置
