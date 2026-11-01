# P6：会话、消息和研究任务归档

工作台现在通过`WorkspaceRepository`保存会话、用户原问题、助手回答、任务状态、阶段事件和已完成角色轨迹。启用MySQL时，这些数据跨进程保留；数据库故障不会静默切回内存。未启用时明确使用进程内实现，页面展示实际存储模式。

## 运行与数据表

Compose已经设置`STOCK_AGENT_MYSQL_ENABLED=true`，构建/启动后自动启用：

```bash
# 8080占用时可换端口。
STOCK_AGENT_HTTP_PORT=18080 ./scripts/dev.sh start
```

宿主JVM使用已有MySQL时，在本地环境配置有效连接后启用：

```text
STOCK_AGENT_MYSQL_ENABLED=true
STOCK_AGENT_MYSQL_URL=jdbc:mysql://127.0.0.1:3307/stock_agent
STOCK_AGENT_MYSQL_USER=你的本地数据库用户
STOCK_AGENT_MYSQL_PASSWORD=你的本地数据库密码
```

显式加载`.env`后运行，步骤见`DEPLOYMENT.md`。数据库账号需能读写目标库并初始化工作台表；连接参数不会通过工作台API返回。API `/api/workspace`中的`historyStorage`为`MYSQL`或`MEMORY`，`historyAvailable`反映当前归档所有权是否可用，不根据容器存在与否猜测。

独立表定义在runtime模块`db/workspace/V1__workspace.sql`：

|表|职责|
|---|---|
|workspace_conversation|UUID、标题、证券、研究日期、最近活动时间|
|workspace_task|UUID、会话关联、用户原问题、实际执行请求、完整轮询快照、状态和写入者|
|workspace_message|每轮一条USER和一条ASSISTANT消息；任务状态通过关联查询获取|

表初始化是幂等的`CREATE TABLE IF NOT EXISTS`，不会清空原表、修改旧的`conversation/research_run`表或导入旧浏览器数据。这是第一版工作台表初始化，不是完整版本迁移系统；未来字段升级需要独立迁移方案，不能通过删卷解决。

## 一轮研究如何保存

1. 校验输入并复制请求；问题最长4000字符，固定会话证券/日期，单会话最多100轮。
2. **入队前**用一个事务创建/检查会话、任务和用户/助手消息。初始助手消息为空，状态为QUEUED。保存失败直接503，不进入模型调度。
3. 有界执行器接收任务后，先保存RUNNING，再执行研究。队列满时保存REJECTED；若此时存储暂时失败，保留补写对象，不遗留永远QUEUED的任务。
4. `onProgress`保存真实阶段事件，最多64条；`onTrace`独立保存已完成角色的实际输入、输出和耗时，最多8项。
5. 完成后，任务终态、完整结果和助手正文在同一个事务内提交；失败、取消和超时也保存明确状态。终态不能被迟到结果或旧进程改回运行中。
6. 查询历史只读取归档，绝不自动调用模型。原问题与包含追问背景的实际模型输入分开保存，不把完整提示词当作用户原文展示。

角色轨迹在完成时及时保存。因此，第三个角色失败时，已经完成的前两个角色仍可查询；失败角色本身没有成功输出时不会伪造一份轨迹。阶段事件、失败阶段和安全错误说明保留。这些轨迹不是每一次底层HTTP尝试的完整日志。Harness已实现可信真实usage结算（见[预算说明](GRAPH.md)），但工作台轨迹不构成供应商计费账单。

## 重启与取消边界

- 新进程接收请求前，将以前QUEUED/RUNNING任务标记为`INTERRUPTED`，并更新助手中断说明。
- 正常关闭也先中断任务、取消自有执行器，再释放数据库所有权连接。
- COMPLETED、FAILED、CANCELLED、TIMED_OUT、REJECTED等既有终态保持原样。
- **不自动续跑、重放或重试模型请求**。需要继续时，用户明确发起新任务。
- 取消先原子迁移终态，再中断执行；取消后的角色轨迹/结果不重新发布。模型请求使用连接/响应正文超时，且会按当前阶段剩余截止时间收紧；取消同时中断有界执行器。
- 保存成功结果失败时，不把未归档正文当作成功返回。系统保留安全失败状态用于补写，明确提示模型可能已经调用，避免用户误以为没有费用。
- 如果提交响应丢失，客户端只查询已有任务或重新加载会话核对；POST不自动重发。不承诺跨任意网络错误的“恰好一次”执行。

20分钟保留期只影响执行对象缓存，MySQL历史不会随缓存清理消失。单会话最多100轮，列表每次最多100条；前端以50条加载更多。进程内实现额外限制总任务500条，它跨重启不保留。

## 单活与事务一致性

本项目是单活学习应用。同一个数据库同时只允许一个工作台写入者；第二个实例启动失败，避免它把另一个正在运行的实例的任务误判为中断。

MySQL命名锁由独立持有的连接维护。所有写事务直接在**这同一个连接**上串行执行；查询使用独立短连接。这样连接在写入途中被终止时，事务与所有权同时失效，旧进程不能在检查通过后用另一条连接继续提交新任务。实际测试通过会话行锁阻塞写入，再KILL本测试持有的连接，验证没有新QUEUED记录提交。

归档所有权纳入readiness，周期性检查也避免长期空闲后连接被回收。所有权丢失后就绪503，拒绝新任务，需要重启实例重新获取锁并恢复状态；不会悄悄重连后冒充原写入者。这不是分布式租约或多副本自动接管机制。

命名锁属于MySQL会话，事务提交/回滚不会自动释放，正常或异常断开会话时释放，依据[MySQL 8.4锁函数说明](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)。

## API

```text
GET    /api/workspace                       实际存储能力
POST   /api/research/tasks                  原接口，新增conversationId/displayQuestion
GET    /api/research/tasks/{id}             实时或已归档任务、事件、完整结果与独立traces
DELETE /api/research/tasks/{id}             显式取消，已归档终态保持不变
GET    /api/conversations?limit=50&offset=0  分页会话元数据
GET    /api/conversations/{id}              原问题/范围/任务详情组成的轮次列表
GET    /api/conversations/{id}/messages     USER/ASSISTANT消息与对应任务状态
```

会话随第一轮任务事务创建，不单独创建无内容的空会话。旧客户端不传`conversationId`时，每个请求自动创建一个会话，响应包含实际关联ID。传入已有会话时证券/日期必须匹配，范围可以按轮次切换。SQL使用绑定参数，JSON恢复不启用多态类型反序列化。

**持久化入口是工作台 `/api/research/tasks`。**原同步`/api/research/chat*`用于角色/数据学习，未自动创建会话。最小`/api/research/runs`演示接口保留自己的内存Repository，不与UUID工作台历史混用；底层旧`JdbcResearchRunRepository`也保留供原学习示例使用。

## 前端恢复

MySQL模式先读取服务端会话元数据，打开会话时再读取完整报告，避免一次拉取全部轨迹。全部查询成功后才替换缓存；列表成功但详情失败时，原离线报告仍可浏览，同时禁止继续发送未确认的研究。上传文档不会重置历史状态。

浏览器localStorage仅缓存最近8个会话、每个最近10轮。缓存上限不影响MySQL历史。MySQL缓存使用独立键，不覆盖同一站点地址下的原浏览器历史；存在旧记录时提供本地JSON导出入口，不自动上传或假装已经入库。跨浏览器访问同一服务可以读取同一数据库历史；这是本地无鉴权项目的共享历史，不是多用户身份隔离或云同步服务。研究问题、证据原文和角色输入输出都包含在归档中。

保存历史也不等于已经完成Agent上下文管理：模型仍只接收当前入口明确选择的背景和证据，不会自动读取数据库全部消息。P7已接入上下文压缩：服务端从会话历史提取有限背景，保留当前问题和关键事实，按分数选择完整证据，并在结果中记录压缩前后 UTF-8 字节估算。

## 学习入口与验收

|入口|可学习知识|
|---|---|
|WorkspaceRepository与两种实现|接口隔离、依赖倒置、显式存储策略|
|JdbcWorkspaceRepository.create/save|入队前持久化、事务原子性、条件更新和终态竞争|
|ResearchTaskService|执行生命周期与归档分离、失败补写、取消、缓存与持久数据边界|
|ResearchProgressListener.onTrace|独立观察回调、部分成功轨迹、避免失败丢证据|
|ConversationController与前端恢复|分页元数据、详情按需加载、只读恢复与避免重复执行|
|JdbcWorkspaceIntegrationTest|真实行锁、所有权连接失效、数据库故障注入与原子回滚|

默认：`mvn test`和`npm run test:ui`。显式MySQL联调需要先配置有效的本地测试库，使用独立实例/测试库，不在正在提供服务的数据库上启动第二个工作台写入者：

```bash
WORKSPACE_MYSQL_INTEGRATION=true mvn -pl stock-agent-runtime -am test \
  -Dtest=JdbcWorkspaceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

集成测试会初始化独立工作台表，仅清理自己生成的UUID记录；第二项测试需要终止测试账号自己持有的连接，并读取自身进程信息。默认不启用，不扫描或终止其他连接。

集成测试覆盖MySQL归档、角色轨迹、所有权故障503、未完成任务中断与历史恢复。请使用独立测试实例；示例财务资料为合成文本。
