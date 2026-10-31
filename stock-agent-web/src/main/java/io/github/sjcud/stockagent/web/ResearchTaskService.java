package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.research.ResearchMode;
import io.github.sjcud.stockagent.research.ResearchProgressListener;
import java.util.*;
import java.util.concurrent.*;

/** 本地工作台的有界内存任务队列；状态轮询和取消不依赖浏览器连接是否仍存在。 */
public class ResearchTaskService implements AutoCloseable {
    /** 可替换执行策略，测试无需真实模型。 */
    private final TaskAction action;
    /** 同时运行2个研究，最多排队8个，拒绝无限堆积。 */
    private final ExecutorService workers = new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8),new ThreadPoolExecutor.AbortPolicy());
    /** 线程安全任务注册表，保留已完成结果便于短时间重新连接。 */
    private final Map<String,Task> tasks = new ConcurrentHashMap<String,Task>();
    /** 统一截止与过期清理，不为每个轮询创建线程。 */
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    /** 总执行/排队时限，单位毫秒。 */
    private static final long DEADLINE = 180000L;
    /** 终态记录保留20分钟，服务重启不保留。 */
    private static final long RETENTION = 1200000L;
    /** 注入执行策略并启动轻量清理任务。 */
    public ResearchTaskService(TaskAction action) {
        this.action=Objects.requireNonNull(action,"action");
        clock.scheduleWithFixedDelay(this::sweep,1,1,TimeUnit.SECONDS);
    }
    /** 验证并复制请求后排队；容量满时拒绝，不能返回一个永远无法执行的任务ID。 */
    public synchronized TaskView start(TaskRequest request) {
        sweep();
        if (tasks.size()>=100) { throw new RejectedExecutionException("任务记录已满"); }
        TaskRequest input=Objects.requireNonNull(request,"request").copy();
        Task task=new Task(); tasks.put(task.id,task);
        try {
            Future<?> future=workers.submit(() -> {
                if (!task.begin()) { return; }
                try {
                    Object result=action.execute(input,task::progress);
                    task.finish(result);
                } catch (Exception exception) { task.fail(exception); }
            });
            task.attach(future);
        } catch (RejectedExecutionException exception) { tasks.remove(task.id); throw exception; }
        return task.view();
    }
    /** 返回防御性状态快照，不暴露内部可变事件列表。 */
    public TaskView get(String id) { return required(id).view(); }
    /** 取消是终态原子迁移，已完成研究不会被改成已取消。 */
    public TaskView cancel(String id) { Task task=required(id); task.stop("CANCELLED"); ((ThreadPoolExecutor)workers).purge(); return task.view(); }
    /** 查找仍保留的任务。 */
    private Task required(String id) {
        Task task=tasks.get(id); if (task==null) { throw new NoSuchElementException("任务已过期"); } return task;
    }
    /** 到期中断进行中的工作，清理过期终态，结果不会永久占用内存。 */
    private void sweep() {
        long now=System.currentTimeMillis();
        for (Task task:tasks.values()) {
            if (now-task.createdAt>=DEADLINE) { task.stop("TIMED_OUT"); }
            if (task.expired(now)) { tasks.remove(task.id,task); }
        }
        ((ThreadPoolExecutor)workers).purge();
    }
    /** 应用关闭时取消工作并关闭所有本服务拥有的执行器。 */
    @Override public void close() {
        for (Task task:tasks.values()) { task.stop("CANCELLED"); }
        workers.shutdownNow(); clock.shutdownNow(); tasks.clear();
    }
    /** 后台工作策略只接收请求和进度观察者，不持有 HTTP 响应对象。 */
    public interface TaskAction {
        /** 返回市场研究或资料问答响应。 */
        Object execute(TaskRequest request,ResearchProgressListener progress) throws Exception;
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
        /** 当前证券代码。 */
        private String symbol;
        /** 当前日终研究日期。 */
        private String asOf;
        /** 当前资料范围。 */
        private Scope scope=Scope.MARKET;
        /** 独立角色串行或并行调度。 */
        private ResearchMode mode=ResearchMode.PARALLEL;
        /** 返回问题。 */
        public String getQuestion() { return question; }
        /** 设置问题。 */
        public void setQuestion(String question) { this.question=question; }
        /** 返回代码。 */
        public String getSymbol() { return symbol; }
        /** 设置代码。 */
        public void setSymbol(String symbol) { this.symbol=symbol; }
        /** 返回日期。 */
        public String getAsOf() { return asOf; }
        /** 设置日期。 */
        public void setAsOf(String asOf) { this.asOf=asOf; }
        /** 返回范围。 */
        public Scope getScope() { return scope; }
        /** 设置范围。 */
        public void setScope(Scope scope) { this.scope=scope; }
        /** 返回编排模式。 */
        public ResearchMode getMode() { return mode; }
        /** 设置编排模式。 */
        public void setMode(ResearchMode mode) { this.mode=mode; }
        /** 脱离控制器请求对象，避免入队后被修改。 */
        private TaskRequest copy() {
            TaskRequest value=new TaskRequest(); value.question=question; value.symbol=symbol;
            value.asOf=asOf; value.scope=scope; value.mode=mode; return value;
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
        private ProgressEvent(String phase,String role,String status) {
            this.phase=phase; this.role=role; this.status=status; this.at=System.currentTimeMillis();
        }
        /** 返回阶段。 */
        public String getPhase() { return phase; }
        /** 返回角色。 */
        public String getRole() { return role; }
        /** 返回状态。 */
        public String getStatus() { return status; }
        /** 返回时间戳。 */
        public long getAt() { return at; }
    }
    /** 轮询响应，只有成功终态才包含研究正文。 */
    public static class TaskView {
        /** 任务ID。 */
        private final String id;
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
        /** 复制内部状态，事件列表不可修改。 */
        private TaskView(Task task) {
            id=task.id; status=task.status; phase=task.phase; error=task.error; result=task.result;
            events=Collections.unmodifiableList(new ArrayList<ProgressEvent>(task.events));
        }
        /** 返回ID。 */
        public String getId() { return id; }
        /** 返回状态。 */
        public String getStatus() { return status; }
        /** 返回阶段。 */
        public String getPhase() { return phase; }
        /** 返回错误说明。 */
        public String getError() { return error; }
        /** 返回执行事件。 */
        public List<ProgressEvent> getEvents() { return events; }
        /** 返回成功结果。 */
        public Object getResult() { return result; }
    }
    /** 所有状态迁移均在任务锁内完成，保证取消胜出后迟到结果无法重新发布。 */
    private static class Task {
        /** 随机、不含问题内容的任务ID。 */
        private final String id=UUID.randomUUID().toString();
        /** 创建时刻，排队也计入总时限。 */
        private final long createdAt=System.currentTimeMillis();
        /** 最后一次状态变化时间。 */
        private long updatedAt=createdAt;
        /** 只允许一次进入终态。 */
        private String status="QUEUED";
        /** 最近执行阶段。 */
        private String phase="QUEUE";
        /** 安全错误描述。 */
        private String error="";
        /** 成功响应，取消时不保留迟到正文。 */
        private Object result;
        /** 有界事件集合，当前阶段数量远小于64。 */
        private final List<ProgressEvent> events=new ArrayList<ProgressEvent>();
        /** 与执行器关联的取消句柄。 */
        private Future<?> future;
        /** 检查是否已结束。 */
        private boolean terminal() { return !"QUEUED".equals(status) && !"RUNNING".equals(status); }
        /** 正式执行前竞争状态。 */
        private synchronized boolean begin() {
            if (terminal()) { return false; } status="RUNNING"; updatedAt=System.currentTimeMillis(); return true;
        }
        /** 句柄晚于取消到达时仍立即取消，处理入队与关闭的竞争。 */
        private synchronized void attach(Future<?> value) {
            future=value; if ("CANCELLED".equals(status) || "TIMED_OUT".equals(status)) { future.cancel(true); }
        }
        /** 只记录仍运行的任务，避免取消后继续出现完成事件。 */
        private synchronized void progress(String phase,String role,String state) {
            if (terminal()) { return; }
            this.phase=phase; if (events.size()<64) { events.add(new ProgressEvent(phase,role,state)); }
            updatedAt=System.currentTimeMillis();
        }
        /** 发布结果前再次检查终态。 */
        private synchronized void finish(Object value) {
            if (terminal()) { return; } result=value; status="COMPLETED"; updatedAt=System.currentTimeMillis();
        }
        /** 公开失败不包含供应商响应、密钥或堆栈。 */
        private synchronized void fail(Exception exception) {
            if (terminal()) { return; }
            status="FAILED"; error=exception instanceof IllegalArgumentException ? "代码、日期或问题无效，请检查后重试。"
                    : "本次研究未完成，请检查数据或模型服务后重试。";
            updatedAt=System.currentTimeMillis();
        }
        /** 原子地终止，发出线程中断信号并释放队列中的句柄。 */
        private synchronized void stop(String value) {
            if (terminal()) { return; }
            status=value; error="TIMED_OUT".equals(value) ? "研究超过3分钟，请稍后重试。" : "";
            updatedAt=System.currentTimeMillis(); if (future!=null) { future.cancel(true); }
        }
        /** 终态记录才可因保留期而删除。 */
        private synchronized boolean expired(long now) { return terminal() && now-updatedAt>RETENTION; }
        /** 在锁内复制完整一致的轮询快照。 */
        private synchronized TaskView view() { return new TaskView(this); }
    }
}
