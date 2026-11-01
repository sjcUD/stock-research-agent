# 本地运行与部署

本文说明如何从源码启动、检查、停止和恢复应用。运行基线JDK 21，Java源码保持Java 8风格。当前没有鉴权和真实交易功能，Compose只把应用端口绑定到本机回环地址。

## 三服务Compose

需要Docker/OrbStack和Docker Compose V2（支持`up --wait`）。Docker负责构建，不要求宿主机预装Maven/JDK；首次构建需要下载镜像和Maven依赖。

在仓库根目录：

```bash
# 已存在.env时保留原配置。
test -f .env || cp .env.example .env
# 编辑.env，填写Chat与Embedding的模型、Base URL、API Key和维度。
./scripts/dev.sh start
./scripts/dev.sh check
```

默认访问 `http://127.0.0.1:8080`。如果端口已占用：

```bash
STOCK_AGENT_HTTP_PORT=18080 ./scripts/dev.sh start
```

端口也可写入`.env`。容器内部应用始终使用8080；Qdrant和MySQL不映射宿主端口，应用通过`qdrant:6334`和`mysql:3306`连接。Compose默认项目名`stock-research-agent`，使用项目隔离的数据卷，不自动导入其他实例的数据。

启动入口会构建镜像、等待MySQL/Qdrant健康、启动应用并等待就绪，失败以非零退出。首次启动需填写真实模型配置；Qdrant初始化可能通过Embedding模型确定向量维度，因此需要访问该端点。Compose显式启用Qdrant、MySQL依赖检查和模型配置检查，与宿主运行时默认关闭Qdrant的配置分开。

默认数据模式是SAMPLE。`STOCK_AGENT_DATA_MODE=LIVE`可使用真实数据源；数据模式和模型配置是两个独立维度。支持`thinking.type`的供应商可按需设置`STOCK_AGENT_CHAT_THINKING_MODE=disabled`，不支持的供应商保持留空。

## 健康与故障定位

```bash
curl -f http://127.0.0.1:8080/actuator/health/liveness
curl -f http://127.0.0.1:8080/actuator/health/readiness
./scripts/dev.sh check
# 日志只读取当前项目的应用，不输出完整Compose解析配置。
docker compose logs --tail=100 app
```

- `liveness`只表示应用进程存活，不把外部服务故障当作必须重启应用的理由。
- `readiness`显式包含`stockDependencies`：工作台归档所有权、启用的Qdrant执行有界gRPC探测，启用的MySQL实际建立连接并关闭。禁用依赖不参与失败判断。
- 模型只检查配置是否完整，不周期性调用收费推理。健康UP不证明模型额度、网络和内容质量可用，真实验证继续使用P11集成测试。
- 健康响应仅公开状态，不输出连接地址、异常正文或密钥；未开放Actuator env/beans/loggers等端点。

依赖故障后就绪返回503。Qdrant恢复后轮询重新检查；P6归档启用后，如果MySQL重启导致单活所有权连接丢失，需要`docker compose restart app`重新获取所有权并中断旧任务，不自动接管。应用镜像内含一个只依赖JRE的HTTP探针，无需安装curl。MySQL连接和socket超时2秒，Qdrant探测等待2秒，探测Future超时/中断会取消；实际总耗时由连接阶段组成，容器探针允许更长读取时间。

## 停止、恢复、清理

```bash
# 删除本项目容器/网络，保留命名卷。
./scripts/dev.sh stop
# 再次启动使用原有数据。
./scripts/dev.sh start
```

MySQL的`mysql-data`和Qdrant的`qdrant-data`均为项目命名卷。MySQL只在首次初始化空卷时运行`V1__baseline.sql`，后续不是自动迁移系统；已有卷也不会因修改初始化密码而重置账号。需要升级表结构或更换模型维度时，应分别规划迁移或新建索引，不能靠重启解决。

清理数据是独立操作，需要同时给出两个明确参数，普通`clean`会拒绝：

```bash
./scripts/dev.sh clean --delete-data --confirm=stock-research-agent
```

它删除当前项目容器和该Compose定义的卷，不执行全局Docker清理。执行后数据库和向量数据无法通过脚本恢复。停服和查询不依赖密钥文件仍然存在；缺少`.env`时仅用无密钥模板解析服务结构，不重新部署。

自定义项目名或配置文件时，每次操作使用相同的设置：

```bash
STOCK_AGENT_COMPOSE_PROJECT=stock-agent-demo \
STOCK_AGENT_ENV_FILE=/absolute/path/to/demo.env \
STOCK_AGENT_HTTP_PORT=18080 ./scripts/dev.sh start
```

自定义项目的清理确认值也须与项目名一致。命名卷按项目隔离，切换项目名不会自动找到旧项目的数据。

## 宿主机运行

宿主机需JDK 21、Maven 3.8+，并自行提供MySQL/Qdrant连接：

```bash
mvn test
mvn -DskipTests install
set -a
. ./.env
set +a
export SPRING_PROFILES_ACTIVE=local
export STOCK_AGENT_QDRANT_ENABLED=true
mvn -pl stock-agent-web spring-boot:run
```

`application-local.properties`默认绑定127.0.0.1，应用端口可由`STOCK_AGENT_HTTP_PORT`覆盖。Qdrant连接地址仍按`.env`配置。显式设置`STOCK_AGENT_MYSQL_ENABLED=true`才启用MySQL依赖检查和工作台归档，默认宿主URL为`jdbc:mysql://127.0.0.1:3307/stock_agent`；用户名/密码必须与已有实例一致。

`mvn -pl stock-agent-web -am package -DskipTests`也会生成可执行JAR，可用`java -jar stock-agent-web/target/stock-agent-web-0.1.0-SNAPSHOT.jar`启动。构建JAR不自动加载`.env`，仍需显式提供环境变量。

## 当前持久化边界

Compose启用MySQL后，工作台会话、消息、任务状态和角色轨迹写入独立workspace表，首次启动自动幂等建表。重启后历史可查询，未完成任务标记INTERRUPTED，不自动继续模型调用；浏览器只保留有限缓存。最小research/runs演示接口仍为内存实现。单个数据库只允许一个工作台写入者，详情见[PERSISTENCE.md](PERSISTENCE.md)。上下文裁剪、模型HTTP超时与父/阶段预算已接入研究流程，真实usage按可信分项结算；这些运行保护不等同于供应商账单对账。

## 日志与构建上下文

应用Console日志通过`SensitiveDataLayout`处理最终正文与异常堆栈。字面脱敏值来自环境变量或JVM属性的Chat/Embedding/Qdrant API Key和MySQL密码；另处理常见Authorization、api_key、password等字段。它不修改模型输入或业务数据，也不是对所有未知敏感信息的自动识别器。文档内容、个人信息或通过其他Spring配置来源传入的任意秘密，不应假定都会被覆盖；推荐使用本项目明确支持的环境变量配置。

不要用`docker compose config`直接展示解析结果，里面包含实际密钥。配置语法校验使用`docker compose config --quiet`。`.env`不进入Git或构建上下文；Docker只允许模块pom/main源码和健康探针进入构建，模块本地data/dist、报告、计划和研究资料均不在允许列表。命名为`data`的Java源码包仍正常保留。

## 可以学习什么

1. 可执行包与多阶段镜像：把构建环境和运行环境分开，运行时采用非root UID。
2. 服务发现与启动顺序：使用容器服务名和健康依赖，不把进程已启动当作服务可用。
3. 存活与就绪：区分外部依赖故障、进程故障和模型配置完整性。
4. 生命周期与资源释放：优雅关闭、探测截止、Future取消、JDBC连接关闭。
5. 状态边界：区分命名卷持久化、应用内存和浏览器历史，避免“有数据库就已经持久化”的误解。
6. 配置与日志边界：密钥只在运行时注入、构建上下文采用允许列表、异常输出统一脱敏。

设计依据：[Docker Compose启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)、[Spring Boot健康端点与探针](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)。官方文档说明readiness不会自动包含额外健康检查，因此本项目显式加入依赖聚合器。

## 模型返回空正文或研究仅完成部分角色

部分推理模型会把推理 Token 计入 `max_tokens`，额度不足可能在回答正文前截断。
例如本地 `deepseek-v4-flash`、2048 Token、未显式设置 thinking 时曾出现部分角色失败；
显式设置 `STOCK_AGENT_CHAT_THINKING_MODE=disabled` 后五分析师与经理示例流程通过。
仅对支持 `thinking.type` 的供应商使用此参数，不根据模型名称自动发送扩展字段。
修改 `.env` 后执行 `./scripts/dev.sh start` 以重建容器配置。
若需要推理模式，应一并评估输出额度、Harness Token预算和阶段时限，不只放大单个参数。
服务日志会记录任务ID、阶段和异常类型/位置链，不输出供应商异常正文。

## 配置生效范围

`.env`只由Compose插值或宿主shell显式加载。存在于模板中不代表该字段已传入容器；不要把`.env`当作容器内自动加载的Spring配置文件。

| 配置组 | Compose | 宿主JVM |
| --- | --- | --- |
| Chat API Key/Base URL/Model/Max Tokens/Thinking | 从`.env`传入 | 导出同名变量 |
| Embedding API Key/Base URL/Model/Dimensions | 从`.env`传入 | 导出同名变量 |
| Qdrant collection、RAG chunk size | 从`.env`传入 | 导出同名变量 |
| Qdrant enabled/host/gRPC端口 | 固定为true/qdrant/6334 | 按实际服务导出变量 |
| MySQL enabled/URL/user | 固定为true、容器URL、stock | 按实际服务导出变量 |
| MySQL password/root password | 从`.env`传入；已有卷不会自动更新账号密码 | password用于应用；root password不属于应用连接参数 |
| HTTP宿主端口 | `.env`控制映射，容器内固定8080 | local profile读取同名变量 |
| require models | 固定true | local profile读取同名变量 |
| 数据mode/连接超时/读取超时/限流 | 从`.env`传入 | 导出同名变量 |
| 公告全文、财报URL、情绪URL、缓存参数 | 当前未透传，需修改Compose environment后重建 | 导出模板中的同名变量 |
| 文档目录/容量/OCR参数 | 当前未透传，使用应用默认；文档卷挂载/app/data | 导出模板中的同名变量，安装OCR程序 |

可选财报URL须返回`{"data":[{"reportPeriod":"2025-12-31","publishedAt":"2026-03-01","currency":"CNY","revenue":"120","netProfit":"9"}]}`；情绪URL须返回`{"asOf":"2026-03-01","score":"0.2","sampleCount":20}`。URL模板必须含`%s`供证券身份替换。这是适配契约，不是任意东方财富页面或社交平台接口的通用解析器。字段含义、单位、权限、披露日期和历史可得性由接入者校验，默认未配置时返回UNAVAILABLE。

## 部署后验收

1. `./scripts/dev.sh check`验证依赖就绪；这不验证模型生成。
2. `python3 examples/first-research/run.py`验证真实Embedding、索引、Chat与引用回链，需要Python 3并产生模型调用。
3. 按[FIRST_STEPS.md](FIRST_STEPS.md)核对回答数字、推断边界和拒答；自动引用核对不替代语义核验。
4. 修改数据模式或其他Compose映射变量后重新运行`start`，检查工作台显示的实际模式。

## 发布验证记录

2026-11-01，以提交`ee5e8eb`的干净Git克隆（不含原工作区构建产物）执行默认后端测试202项，失败0、外部测试跳过16；前端16项通过。独立Compose项目使用空业务卷从源码构建，首页及依赖就绪可访问；公开合成财报经过真实Embedding、Qdrant、Chat和原文偏移核验，市场研究返回五分析师与经理六条完成轨迹。本记录只证明该版本和当次配置下的结果，不代表所有供应商或数据源都已验证。后续整理仅涉及说明与实验预期，不改变已验证业务代码。
