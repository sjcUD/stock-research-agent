# P12 本地运行与部署

目标是让学习项目可以从源码重复启动、检查、停止和恢复。运行基线JDK 21，Java源码保持Java 8风格。当前没有鉴权和真实交易功能，Compose只把应用端口绑定到本机回环地址。

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

端口也可写入`.env`。容器内部应用始终使用8080；Qdrant和MySQL不映射宿主端口，应用通过`qdrant:6334`和`mysql:3306`连接。Compose默认项目名`stock-research-agent`，不会接管以前手工创建的`stock-agent-qdrant`、`stock-agent-mysql`容器，它们的数据彼此独立。

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

现有手工容器可继续用于开发，无需迁移到Compose：

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

Compose启用MySQL后，工作台会话、消息、任务状态和角色轨迹写入独立workspace表，首次启动自动幂等建表。重启后历史可查询，未完成任务标记INTERRUPTED，不自动继续模型调用；浏览器只保留有限缓存。最小research/runs演示接口仍为内存实现。单个数据库只允许一个工作台写入者，详情见[PERSISTENCE.md](PERSISTENCE.md)。P7上下文压缩、模型HTTP超时和阶段预算继续保留在TODO。

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

## 本阶段验收记录

2026-11-01：独立Compose项目在18080端口实际从源码构建，三服务healthy；工作台、SAMPLE行情、合成资料入库/检索与真实模型引用回答通过。分别停止MySQL/Qdrant时readiness503而liveness200，恢复后readiness200；stop/start后SQL测试记录与向量引用保留。自己的SQL标记已删除，未修改原手工容器。日志扫描未出现配置的模型密钥或数据库密码。

构建上下文校验使用确实存在的合成模块data标记，断言其不进入镜像，并同时断言Java data源码包仍在。该测试先失败再通过，避免仅靠阅读忽略规则得出安全结论。默认全量Java113项，104通过、9项外部服务测试跳过；前端9项通过。
