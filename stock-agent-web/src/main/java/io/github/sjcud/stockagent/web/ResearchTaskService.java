package io.github.sjcud.stockagent.web;

import com.fasterxml.jackson.databind.*;

import io.github.sjcud.stockagent.research.AgentTrace;
import io.github.sjcud.stockagent.research.ResearchMode;
import io.github.sjcud.stockagent.research.ResearchEngine;
import io.github.sjcud.stockagent.research.ResearchProgressListener;
import io.github.sjcud.stockagent.runtime.workspace.*;

import java.util.*;
import java.util.concurrent.*;

/** 有界工作队列与可替换归档组合；状态、消息和轨迹不依赖浏览器连接。 */
public class ResearchTaskService implements AutoCloseable {
    /** 可替换执行策略，测试无需真实模型。 */
    private final TaskAction action;

    /** 可替换持久化策略；MySQL启用后故障不回退到内存。 */
    private final WorkspaceRepository archive;

    /** 只在web边界编码归档JSON，runtime仓储不依赖领域响应类型。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 关闭后不再允许入队，防止出现未被中断的关闭竞争。 */
    private boolean closed;

    /** 同时运行2个研究，最多排队8个，拒绝无限堆积。 */
    private final ExecutorService workers =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(8),
                    new ThreadPoolExecutor.AbortPolicy());

    /** 线程安全任务注册表，保留已完成结果便于短时间重新连接。 */
    private final Map<String, Task> tasks = new ConcurrentHashMap<String, Task>();

    /** 统一截止与过期清理，不为每个轮询创建线程。 */
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();

    /** 总执行/排队时限，单位毫秒。 */
    private static final long DEADLINE = 180000L;

    /** 执行对象缓存保留20分钟；MySQL归档独立保留，不随缓存过期删除。 */
    private static final long RETENTION = 1200000L;

    /** 注入执行策略并启动轻量清理任务。 */
    public ResearchTaskService(TaskAction action) {
        this(action, new InMemoryWorkspaceRepository());
    }

    /** 在接收任务前恢复未完成状态，绝不自动重新执行旧请求。 */
    public ResearchTaskService(TaskAction action, WorkspaceRepository archive) {
        this.action = Objects.requireNonNull(action, "action");
        this.archive = Objects.requireNonNull(archive, "archive");
        archive.interruptUnfinished();
        clock.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.SECONDS);
    }

    /** 验证并复制请求后排队；容量满时拒绝，不能返回一个永远无法执行的任务ID。 */
    public synchronized TaskView start(TaskRequest request) {
        if (closed) throw new RejectedExecutionException("任务服务已关闭");
        sweep();
        if (tasks.size() >= 100) {
            throw new RejectedExecutionException("任务记录已满");
        }
        TaskRequest input = Objects.requireNonNull(request, "request").copy();
        if (input.conversationId == null) input.conversationId = UUID.randomUUID().toString();
        UUID.fromString(input.conversationId);
        if (input.historyContext == null) {
            StringBuilder history = new StringBuilder();
            for (WorkspaceRepository.Entry previous : archive.entries(input.conversationId)) {
                history.append("用户：").append(previous.question).append('\n');
                JsonNode snapshot = read(previous.snapshotJson);
                if (snapshot.has("result")) history.append("助手：").append(snapshot.get("result")).append('\n');
            }
            input.historyContext = history.toString();
        }
        if (input.displayQuestion == null) input.displayQuestion = input.question;
        if (input.displayQuestion == null
                || input.displayQuestion.trim().isEmpty()
                || input.displayQuestion.length() > 4000)
            throw new IllegalArgumentException("问题无效");
        Task task = new Task(input);
        WorkspaceRepository.Conversation conversation =
                new WorkspaceRepository.Conversation(
                        input.conversationId,
                        input.displayQuestion.substring(
                                0, Math.min(100, input.displayQuestion.length())),
                        input.symbol,
                        input.asOf,
                        input.scope.name(),
                        task.createdAt,
                        task.createdAt);
        // 保存成功才允许进入执行器：数据库故障时不会产生隐藏的收费任务。
        archive.create(conversation, task.entry());
        tasks.put(task.id, task);
        try {
            Future<?> future =
                    workers.submit(
                            () -> {
                                if (!task.begin()) {
                                    return;
                                }
                                try {
                                    Object result = action.execute(input, task.listener());
                                    task.finish(result);
                                } catch (Exception exception) {
                                    task.fail(exception);
                                }
                            });
            task.attach(future);
        } catch (RejectedExecutionException exception) {
            task.stop("REJECTED");
            if (!task.pendingWrite()) tasks.remove(task.id);
            throw exception;
        }
        return get(task.id);
    }

    /** 返回防御性状态快照，不暴露内部可变事件列表。 */
    public TaskView get(String id) {
        Task local = tasks.get(id);
        if (local != null) local.flush();
        WorkspaceRepository.Entry stored = archive.get(id);
        if (local != null) {
            TaskView view = local.view();
            if (view.status.equals(stored.status)) return view;
        }
        return restored(stored);
    }

    /** 会话列表只含元数据，不在列表中加载全部报告。 */
    public List<WorkspaceRepository.Conversation> conversations(int limit, int offset) {
        return archive.conversations(limit, offset);
    }

    /** 返回完整会话轮次，保留原问题、范围与归档任务快照。 */
    public List<Map<String, Object>> history(String conversationId) {
        List<WorkspaceRepository.Entry> entries = archive.entries(conversationId);
        if (entries.isEmpty()) throw new NoSuchElementException("会话不存在");
        List<Map<String, Object>> turns = new ArrayList<Map<String, Object>>();
        for (WorkspaceRepository.Entry e : entries) {
            Map<String, Object> turn = new LinkedHashMap<String, Object>();
            JsonNode request = read(e.requestJson);
            turn.put("id", e.id);
            turn.put("taskId", e.id);
            turn.put("question", e.question);
            turn.put("scope", request.path("scope").asText("MARKET"));
            turn.put("task", get(e.id));
            turns.add(turn);
        }
        return turns;
    }

    /** 独立消息查询包含用户原文、助手回答及对应任务状态。 */
    public List<WorkspaceRepository.Message> messages(String cid) {
        return archive.messages(cid);
    }

    /** 能力标记由实际仓储返回，不根据数据库端口存在猜测。 */
    public boolean durable() {
        return archive.durable();
    }

    /** 取消是终态原子迁移，已完成研究不会被改成已取消。 */
    public TaskView cancel(String id) {
        Task task = tasks.get(id);
        if (task != null) task.stop("CANCELLED");
        ((ThreadPoolExecutor) workers).purge();
        return get(id);
    }

    /** 到期中断进行中的工作，清理过期终态，结果不会永久占用内存。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            task.flushQuietly();
            if (now - task.createdAt >= DEADLINE) {
                task.stop("TIMED_OUT");
            }
            if (task.expired(now)) {
                tasks.remove(task.id, task);
            }
        }
        ((ThreadPoolExecutor) workers).purge();
    }

    /** 应用关闭时取消工作并关闭所有本服务拥有的执行器。 */
    @Override
    public synchronized void close() {
        closed = true;
        for (Task task : tasks.values()) {
            task.stop("INTERRUPTED");
        }
        workers.shutdownNow();
        clock.shutdownNow();
        tasks.clear();
    }

    /** 后台工作策略只接收请求和进度观察者，不持有 HTTP 响应对象。 */
    public interface TaskAction {
        /** 返回市场研究或资料问答响应。 */
        Object execute(TaskRequest request, ResearchProgressListener progress) throws Exception;
    }

    /** 请求中的资料范围。 */
    public enum Scope {
        /** 仅使用可追溯市场数据。 */
        MARKET,
        /** 显式使用通用 RAG，不承诺历史披露时点过滤。 */
        DOCUMENTS
    }

    /** 工作台研究请求，不接受用户手填报价。 */
    public static class TaskRequest {
        /** 用户问题及同范围有限追问背景。 */
        private String question;

        /** 服务端会话关联，缺省时为兼容旧客户端自动新建。 */
        private String conversationId;

        /** 用户原问题，与包含追问背景的模型输入分开保存。 */
        private String displayQuestion;

        /** 服务端整理的历史背景，不接受客户端直接注入。 */
        private String historyContext;

        /** 返回当前会话的历史背景。 */
        public String getHistoryContext() { return historyContext; }

        /** 返回会话ID。 */
        public String getConversationId() {
            return conversationId;
        }

        /** 设置会话ID。 */
        public void setConversationId(String value) {
            conversationId = value;
        }

        /** 返回用户原问题。 */
        public String getDisplayQuestion() {
            return displayQuestion;
        }

        /** 设置用户原问题。 */
        public void setDisplayQuestion(String value) {
            displayQuestion = value;
        }

        /** 当前证券代码。 */
        private String symbol;

        /** 当前日终研究日期。 */
        private String asOf;

        /** 当前资料范围。 */
        private Scope scope = Scope.MARKET;

        /** 独立角色串行或并行调度。 */
        private ResearchMode mode = ResearchMode.PARALLEL;

        /** 单次研究实际选择的引擎；旧客户端省略时保留Java策略。 */
        private ResearchEngine engine = ResearchEngine.JAVA_EXECUTOR;

        /** 返回引擎，将随请求JSON归档。 */
        public ResearchEngine getEngine() { return engine; }

        /** 设置引擎，控制器负责校验资料问答不支持Graph。 */
        public void setEngine(ResearchEngine engine) { this.engine = engine; }

        /** 返回问题。 */
        public String getQuestion() {
            return question;
        }

        /** 设置问题。 */
        public void setQuestion(String question) {
            this.question = question;
        }

        /** 返回代码。 */
        public String getSymbol() {
            return symbol;
        }

        /** 设置代码。 */
        public void setSymbol(String symbol) {
            this.symbol = symbol;
        }

        /** 返回日期。 */
        public String getAsOf() {
            return asOf;
        }

        /** 设置日期。 */
        public void setAsOf(String asOf) {
            this.asOf = asOf;
        }

        /** 返回范围。 */
        public Scope getScope() {
            return scope;
        }

        /** 设置范围。 */
        public void setScope(Scope scope) {
            this.scope = scope;
        }

        /** 返回编排模式。 */
        public ResearchMode getMode() {
            return mode;
        }

        /** 设置编排模式。 */
        public void setMode(ResearchMode mode) {
            this.mode = mode;
        }

        /** 脱离控制器请求对象，避免入队后被修改。 */
        private TaskRequest copy() {
            TaskRequest value = new TaskRequest();
            value.question = question;
            value.symbol = symbol;
            value.asOf = asOf;
            value.scope = scope;
            value.mode = mode;
            value.engine = engine;
            value.conversationId = conversationId;
            value.displayQuestion = displayQuestion;
            value.historyContext = historyContext;
            return value;
        }
    }

    /** 单条真实执行事件。 */
    public static class ProgressEvent {
        /** 阶段代码。 */
        private final String phase;

        /** 可选角色标识。 */
        private final String role;

        /** STARTED 或 COMPLETED。 */
        private final String status;

        /** 事件发生时的服务端时间。 */
        private final long at;

        /** 创建不可变事件。 */
        private ProgressEvent(String phase, String role, String status) {
            this(phase, role, status, System.currentTimeMillis());
        }

        /** 恢复已保存的事件时间，查询不生成新的执行事件。 */
        private ProgressEvent(String phase, String role, String status, long at) {
            this.phase = phase;
            this.role = role;
            this.status = status;
            this.at = at;
        }

        /** 返回阶段。 */
        public String getPhase() {
            return phase;
        }

        /** 返回角色。 */
        public String getRole() {
            return role;
        }

        /** 返回状态。 */
        public String getStatus() {
            return status;
        }

        /** 返回时间戳。 */
        public long getAt() {
            return at;
        }
    }

    /** 轮询响应，只有成功终态才包含研究正文。 */
    public static class TaskView {
        /** 任务ID。 */
        private final String id;

        /** 任务所属会话，客户端据此关联历史。 */
        private final String conversationId;

        /** 任务状态。 */
        private final String status;

        /** 最近阶段。 */
        private final String phase;

        /** 对用户安全的错误说明。 */
        private final String error;

        /** 当前事件快照。 */
        private final List<ProgressEvent> events;

        /** 成功后的领域响应。 */
        private final Object result;

        /** 独立归档的已完成角色轨迹，整体失败时仍可查询。 */
        private final List<AgentTrace> traces;

        /** 复制内部状态，事件列表不可修改。 */
        private TaskView(Task task) {
            id = task.id;
            conversationId = task.input.conversationId;
            status = task.status;
            phase = task.phase;
            error = task.error;
            result = task.result;
            events = Collections.unmodifiableList(new ArrayList<ProgressEvent>(task.events));
            traces = Collections.unmodifiableList(new ArrayList<AgentTrace>(task.traces));
        }

        /** 恢复快照时以数据库状态列为准，重启中断不会显示旧RUNNING或旧结果。 */
        private TaskView(WorkspaceRepository.Entry entry, JsonNode snapshot) {
            id = entry.id;
            conversationId = entry.conversationId;
            status = entry.status;
            phase = snapshot.path("phase").asText("QUEUE");
            error =
                    "INTERRUPTED".equals(status)
                            ? "服务已重启，本次研究中断；请确认后重新发起。"
                            : snapshot.path("error").asText("");
            result = "COMPLETED".equals(status) ? snapshot.get("result") : null;
            List<ProgressEvent> values = new ArrayList<ProgressEvent>();
            for (JsonNode event : snapshot.path("events"))
                values.add(
                        new ProgressEvent(
                                event.path("phase").asText(),
                                event.path("role").asText(),
                                event.path("status").asText(),
                                event.path("at").asLong()));
            events = Collections.unmodifiableList(values);
            List<AgentTrace> restored = new ArrayList<AgentTrace>();
            for (JsonNode trace : snapshot.path("traces"))
                restored.add(
                        new AgentTrace(
                                trace.path("role").asText(),
                                trace.path("input").asText(),
                                trace.path("output").asText(),
                                trace.path("elapsedMillis").asLong()));
            traces = Collections.unmodifiableList(restored);
        }

        /** 返回完整已完成角色轨迹；不代表未完成角色产生过模型输出。 */
        public List<AgentTrace> getTraces() {
            return traces;
        }

        /** 返回会话标识。 */
        public String getConversationId() {
            return conversationId;
        }

        /** 返回ID。 */
        public String getId() {
            return id;
        }

        /** 返回状态。 */
        public String getStatus() {
            return status;
        }

        /** 返回阶段。 */
        public String getPhase() {
            return phase;
        }

        /** 返回错误说明。 */
        public String getError() {
            return error;
        }

        /** 返回执行事件。 */
        public List<ProgressEvent> getEvents() {
            return events;
        }

        /** 返回成功结果。 */
        public Object getResult() {
            return result;
        }
    }

    /** 所有状态迁移均在任务锁内完成，保证取消胜出后迟到结果无法重新发布。 */
    private class Task {
        /** 不可变请求副本，和实际执行请求同时保存。 */
        private final TaskRequest input;

        /** 保存失败时只重试状态写入，不重新执行模型。 */
        private boolean dirty;

        /** 创建未提交任务，外层在入队前事务保存。 */
        private Task(TaskRequest input) {
            this.input = input;
        }

        /** 编码完整事件/响应，轨迹与引用原样归档。 */
        private WorkspaceRepository.Entry entry() {
            return new WorkspaceRepository.Entry(
                    id,
                    input.conversationId,
                    input.displayQuestion,
                    json(input),
                    json(view()),
                    status,
                    createdAt,
                    updatedAt);
        }

        /** 保存后才对外视为成功；写入失败终止研究并保留待补写的安全失败状态。 */
        private boolean publish() {
            try {
                boolean saved = archive.save(entry(), assistantText());
                dirty = false;
                return saved;
            } catch (RuntimeException ex) {
                status = "FAILED";
                error = "历史记录保存失败，本次研究已停止。模型可能已调用，请核对后再决定是否重试。";
                result = null;
                dirty = true;
                return false;
            }
        }

        /** 助手消息保存可读正文；完整结构化结果仍保留在任务快照。 */
        private String assistantText() {
            if (!"COMPLETED".equals(status)) return error;
            JsonNode node = mapper.valueToTree(result);
            if (node.isTextual()) return node.asText();
            JsonNode research = node.has("research") ? node.get("research") : node;
            return research.has("decision")
                    ? research.path("decision").path("decision").asText()
                    : research.path("answer").asText();
        }

        /** 未落库的终态不能丢弃，否则会遗留数据库QUEUED孤儿。 */
        private synchronized boolean pendingWrite() {
            return dirty;
        }

        /** 恢复数据库后仅补写安全终态，永不重放模型。 */
        private synchronized void flush() {
            if (dirty && !publish()) throw new WorkspaceStorageException(null);
        }

        /** 定时清理不能因数据库故障永久退出。 */
        private synchronized void flushQuietly() {
            if (dirty) publish();
        }

        /** 随机、不含问题内容的任务ID。 */
        private final String id = UUID.randomUUID().toString();

        /** 创建时刻，排队也计入总时限。 */
        private final long createdAt = System.currentTimeMillis();

        /** 最后一次状态变化时间。 */
        private long updatedAt = createdAt;

        /** 只允许一次进入终态。 */
        private String status = "QUEUED";

        /** 最近执行阶段。 */
        private String phase = "QUEUE";

        /** 安全错误描述。 */
        private String error = "";

        /** 成功响应，取消时不保留迟到正文。 */
        private Object result;

        /** 有界事件集合，当前阶段数量远小于64。 */
        private final List<ProgressEvent> events = new ArrayList<ProgressEvent>();

        /** 独立角色/经理最多六份，预留八项避免异常无限增长。 */
        private final List<AgentTrace> traces = new ArrayList<AgentTrace>();

        /** 阶段与轨迹使用不同回调，失败任务也不丢失已完成角色。 */
        private ResearchProgressListener listener() {
            return new ResearchProgressListener() {
                public void onProgress(String phase, String role, String status) {
                    progress(phase, role, status);
                }

                public void onTrace(AgentTrace trace) {
                    recordTrace(trace);
                }
            };
        }

        /** 取消/终态后忽略迟到轨迹，保存失败会终止当前研究。 */
        private synchronized void recordTrace(AgentTrace trace) {
            if (terminal() || trace == null) return;
            if (traces.size() < 8) traces.add(trace);
            updatedAt = System.currentTimeMillis();
            if (!publish()) throw new WorkspaceStorageException(null);
        }

        /** 与执行器关联的取消句柄。 */
        private Future<?> future;

        /** 检查是否已结束。 */
        private boolean terminal() {
            return !"QUEUED".equals(status) && !"RUNNING".equals(status);
        }

        /** 正式执行前竞争状态。 */
        private synchronized boolean begin() {
            if (terminal()) {
                return false;
            }
            status = "RUNNING";
            updatedAt = System.currentTimeMillis();
            return publish();
        }

        /** 句柄晚于取消到达时仍立即取消，处理入队与关闭的竞争。 */
        private synchronized void attach(Future<?> value) {
            future = value;
            if (terminal()) {
                future.cancel(true);
            }
        }

        /** 只记录仍运行的任务，避免取消后继续出现完成事件。 */
        private synchronized void progress(String phase, String role, String state) {
            if (terminal()) {
                return;
            }
            this.phase = phase;
            if (events.size() < 64) {
                events.add(new ProgressEvent(phase, role, state));
            }
            updatedAt = System.currentTimeMillis();
            if (!publish()) throw new WorkspaceStorageException(null);
        }

        /** 发布结果前再次检查终态。 */
        private synchronized void finish(Object value) {
            if (terminal()) {
                return;
            }
            result = value;
            status = "COMPLETED";
            updatedAt = System.currentTimeMillis();
            publish();
        }

        /** 公开失败不包含供应商响应、密钥或堆栈。 */
        private synchronized void fail(Exception exception) {
            if (terminal()) {
                return;
            }
            status = "FAILED";
            error =
                    exception instanceof IllegalArgumentException
                            ? "代码、日期或问题无效，请检查后重试。"
                            : "本次研究未完成，请检查数据或模型服务后重试。";
            updatedAt = System.currentTimeMillis();
            publish();
        }

        /** 原子地终止，发出线程中断信号并释放队列中的句柄。 */
        private synchronized void stop(String value) {
            if (terminal()) {
                return;
            }
            status = value;
            error =
                    "TIMED_OUT".equals(value)
                            ? "研究超过3分钟，请稍后重试。"
                            : "INTERRUPTED".equals(value)
                                    ? "服务已重启，本次研究中断；请确认后重新发起。"
                                    : "REJECTED".equals(value) ? "研究队列已满，本次未执行。" : "";
            updatedAt = System.currentTimeMillis();
            publish();
            if (future != null) {
                future.cancel(true);
            }
        }

        /** 终态记录才可因保留期而删除。 */
        private synchronized boolean expired(long now) {
            return terminal() && !dirty && now - updatedAt > RETENTION;
        }

        /** 在锁内复制完整一致的轮询快照。 */
        private synchronized TaskView view() {
            return new TaskView(this);
        }
    }

    /** 归档JSON失败也是存储失败，不降级为不完整的成功记录。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 解析只读取本服务写入的JSON，不开启多态类型反序列化。 */
    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 完全从保存的快照构造响应，查询无模型副作用。 */
    private TaskView restored(WorkspaceRepository.Entry entry) {
        return new TaskView(entry, read(entry.snapshotJson));
    }
}
