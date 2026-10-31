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
