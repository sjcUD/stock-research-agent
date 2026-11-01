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

## Java 包名被运行时目录忽略规则误伤（2026-11-01）

原规则 `**/data/` 也会忽略 `src/main/java/.../stockagent/data` 及对应测试目录，导致本地 Maven 能运行而源码没有进入 Git。P9 将规则收窄为 `/data/` 和 `/*/data/`，只忽略根目录与模块根目录的运行时数据。数据契约和提供器必须随源码提交；验证时同时检查 `git status`、`git check-ignore` 与干净构建，不能仅凭本地测试通过认定仓库可复现。

## P9 HTTP 总超时与客户端自动选择（2026-11-01）

- 只设置连接和 socket 空闲读取超时，不能覆盖缓慢持续发送响应头的上游。独立审查通过本地逐字节响应头复现：200ms 目标时限被拖长到2秒以上。P9 改用可取消的 Apache HttpClient 适配，独立监控总截止和调用线程中断，到期 `HttpGet.cancel()`，并关闭每次请求的连接与监控线程。慢响应头、执行中取消、响应过大和非200均有回归测试。
- 新增 Apache 依赖后，Spring 6.2.11 `DefaultRestClientBuilder.initRequestFactory()` 会优先选择 Apache，模型链路因此与之前不同。真实串/并行市场研究均观察到模型 JSON 提前结束；显式将 ChatModel 的 RestClient 固定为 `JdkClientHttpRequestFactory` 后，同一真实市场研究集成测试通过。这里只记录本次 A/B 观察，不归结为 Spring AI 协议适配失效或 Apache 对所有服务都不可用；供应商网关、HTTP版本等更深层原因未作定论。
- 模型仍通过 Spring AI `OpenAiApi` 适配，不新增手写模型 HTTP 网关。市场数据传输与模型传输各自明确选择，避免 classpath 新依赖无意改变已有行为。
- Java 编译未保留方法参数名时，`@RequestParam` 必须显式写 `symbol` 和 `asOf`；直接调用控制器的单元测试无法发现该绑定错误，因此增加随机本地端口的真实 MVC HTTP 回归。
- 腾讯未复权日线 `param` 的最后一个复权参数为空，末尾逗号仍必须保留；东方财富新闻 JSONP 使用固定回调，禁止执行脚本，也不宽松截取任意括号。资讯时间语义保留原始精度，公告 `notice_date` 不用较早的 `display_time` 替换。

## P10：取消、长文档与入口脚本验证（2026-11-01）

- 停止按钮不能等下一个轮询GET成功再发DELETE：网络挂起时会继续后台研究。独立TaskChannel保存取消意图，拿到任务ID即发送取消，GET/POST设置有限等待；终态不接受迟到快照回退，失败时保留同ID重连。
- Future.cancel不会自动从ThreadPoolExecutor有界队列移除已取消任务。取消后purge，避免队列明明已取消仍拒绝新研究；有填满队列后取消并重新入队的回归。
- Tika默认parseToString可能只返回前100000字符。显式允许读取100万+1字符，在trim之前检查上限，超限拒绝，避免假“索引完成”。长文档尾部进入切片、PDF/Word/Excel解析均有回归。
- 新上传入口会真正处理PDF；按[Apache官方安全说明](https://tika.apache.org/security.html)升级现有Tika至3.3.3，包含既有PDF XFA XXE修复。不把其他解析风险宣称为已全面审计。
- 纯函数测试不能证明入口脚本可解析。整理注释时曾误将async与function断开；已修复并把node --check入口及API脚本加入test:ui。
- 资料问答必须把用户具体问题传给模型；仅用它检索而让模型继续输出固定基本面模板，会偏离问题。资料问答独立于行情源，未知市场报价明确不提供，不编造数值。

## P11：小样例成功不代表批量导入成功

2026-11-01真实参数评测时，DashScope兼容端点对一次超过10条文本返回400，提示 `batch size is invalid ... not be larger than 10`。这是请求条数限制，与 `/compatible-mode/v1` 路径处理无关。Spring AI 按Token分批也不保证满足条数限制。

处理：`SpringAiEmbeddingGateway` 与 `QdrantVectorStore` 在原框架协议外层按条数分批，默认10，构造参数可改；验证输出数量和顺序，保留稳定片段ID让部分批次失败后可覆盖重试。该默认是当前服务实测值，不宣称所有供应商统一限制为10。回归同时覆盖5条按2/2/1提交和真实批量写入。

真实回答评测还发现“保留片段标识”过于模糊，模型可能改写成自然语言出处，导致引用语法无法识别。提示词现要求原样使用 `[片段=原始片段ID]`；评测器仍独立验证实际引用，不因增加提示词便假定模型一定遵守。
