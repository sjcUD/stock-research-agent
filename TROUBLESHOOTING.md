# 踩坑经验

## Spring AI 调用 DashScope Embedding 返回 404

### 现象

使用 Spring AI 的 `OpenAiEmbeddingModel` 调用 DashScope `text-embedding-v4` 时，API Key 和模型名称都正确，但服务返回 `404`。

### 错误配置

```java
OpenAiApi api = OpenAiApi.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(apiKey)
        .build();
```

Spring AI 默认会请求：

```text
/v1/embeddings
```

这会丢失 DashScope 的 `/compatible-mode` 路径前缀，实际请求地址不正确。

### 正确配置

保留完整的 DashScope base URL，同时显式覆盖 Embedding 路径：

```java
OpenAiApi api = OpenAiApi.builder()
        .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        .apiKey(apiKey)
        .embeddingsPath("/embeddings")
        .build();
```

最终请求地址为：

```text
https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings
```

### 项目中的约定

- Spring AI 继续负责 OpenAI 兼容模型适配。
- DashScope、OpenAI 等服务的 `baseUrl` 单独配置。
- 带路径前缀的兼容服务必须显式设置 `embeddingsPath`。
- 向量维度必须和 Qdrant collection 维度一致。
- 真实模型联调使用独立测试，并通过环境变量显式启用，避免普通单元测试产生外部调用。

### 验证方式

```bash
set -a
. ./.env
set +a
EMBEDDING_INTEGRATION=true mvn -pl stock-agent-web -am \
  -Dtest=EmbeddingProviderLiveIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试已使用 DashScope `text-embedding-v4` 和 1024 维配置验证通过。

## 多角色联调返回空报告（2026-11-01）

现象：为快速验证而将 `STOCK_AGENT_CHAT_MAX_TOKENS` 临时降为 512 后，多角色接口返回 502；可选集成测试定位到模型正文为空，`AnalystResult` 拒绝接收空报告。将同一配置的上限恢复为 2048 后，真实五角色分析和组合经理集成测试通过，但后续另一条 HTTP 请求仍失败，不能仅凭一次成功认定额度调整已经解决问题。

最后增加显式的 `STOCK_AGENT_CHAT_THINKING_MODE` 可选配置，通过 Spring AI `extraBody` 发送 `thinking.type`。以 `disabled`、2048 输出上限执行真实 HTTP 验收，返回 200，包含五份报告、六条轨迹和五条带引用证据。未修改用户本地 `.env`；联调通过进程环境变量启用该选项。重启时可按需在本地配置中设置。

[DeepSeek 官方思考模式文档](https://api-docs.deepseek.com/guides/thinking_mode/)列出了 `thinking.type` 的 enabled/disabled 开关。该参数不适用于所有 OpenAI 兼容供应商，因此默认不发送，不根据模型名称自动推断。

这次验证说明输出额度及思考模式配置会影响完整研究任务，不能用“只回答几个字”的模型连通性测试替代完整链路验收。没有检查供应商 `finish_reason` 或 reasoning usage，因此不将具体截断机制当作已经证实的结论。不要在空正文时伪造报告或继续汇总；先检查输出额度、模型响应元数据和供应商服务状态。

可选多角色集成测试直接使用实际 Spring provider 配置，不另造一套 HTTP 适配：

```bash
set -a
. ./.env
set +a
export STOCK_AGENT_QDRANT_ENABLED=true
MULTI_RESEARCH_INTEGRATION=true mvn -pl stock-agent-web -am \
  -Dtest=MultiResearchLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试会调用真实 Embedding、Qdrant、五个分析师和组合经理，需要足够的请求与 Token 额度。数据快照明确标为 SAMPLE；响应不是实时行情结果。
