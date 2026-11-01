package io.github.sjcud.stockagent.web;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** 有界SSE发送通道：模型线程只提交最新快照，慢浏览器不能阻塞模型或数据库事务。 */
final class TaskEventBroadcaster implements AutoCloseable {
    private final Set<Subscription> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor senders =
            new ThreadPoolExecutor(
                    2,
                    2,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<Runnable>(16),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private boolean closed, started;

    /** 最多16连接，每个订阅只保留一个最新快照和一个发送任务。 */
    synchronized AutoCloseable subscribe(
            String id, Consumer<ResearchTaskService.TaskView> sink, Runnable onClose) {
        if (closed || clients.size() >= 16) throw new RejectedExecutionException("增量连接已满");
        if (!started) {
            started = true;
            heartbeat.scheduleAtFixedRate(
                    () -> {
                        for (Subscription c : clients) {
                            c.beat.set(true);
                            c.schedule();
                        }
                    },
                    5,
                    5,
                    TimeUnit.SECONDS);
        }
        Subscription client = new Subscription(id, sink, onClose);
        clients.add(client);
        return client;
    }

    void publish(ResearchTaskService.TaskView view) {
        for (Subscription client : clients)
            if (client.id.equals(view.getId())) {
                client.offer(view);
            }
    }

    /** 仅发送目标连接的初始快照，避免新观察者导致全体连接重复刷新。 */
    void initial(AutoCloseable handle, ResearchTaskService.TaskView view) {
        Subscription client = (Subscription) handle;
        client.offer(view);
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Subscription client : clients) client.close();
        heartbeat.shutdownNow();
        senders.shutdownNow();
    }

    private final class Subscription implements AutoCloseable, Runnable {
        private final String id;
        private final Consumer<ResearchTaskService.TaskView> sink;
        private final Runnable onClose;
        private final AtomicReference<ResearchTaskService.TaskView> pending =
                new AtomicReference<ResearchTaskService.TaskView>();
        private final AtomicBoolean queued = new AtomicBoolean(),
                beat = new AtomicBoolean(),
                stopped = new AtomicBoolean();

        private Subscription(
                String id, Consumer<ResearchTaskService.TaskView> sink, Runnable onClose) {
            this.id = id;
            this.sink = sink;
            this.onClose = onClose;
        }

        private void offer(ResearchTaskService.TaskView value) {
            pending.accumulateAndGet(
                    value,
                    (old, next) ->
                            old != null && old.getVersion() > next.getVersion() ? old : next);
            schedule();
        }

        private void schedule() {
            if (!stopped.get() && queued.compareAndSet(false, true))
                try {
                    senders.execute(this);
                } catch (RejectedExecutionException ex) {
                    close();
                }
        }

        @Override
        public void run() {
            try {
                while (!stopped.get()) {
                    ResearchTaskService.TaskView value = pending.getAndSet(null);
                    if (value != null) {
                        sink.accept(value);
                        if (!"QUEUED".equals(value.getStatus())
                                && !"RUNNING".equals(value.getStatus())) {
                            close();
                            break;
                        }
                    } else if (beat.getAndSet(false)) sink.accept(null);
                    else break;
                }
            } catch (RuntimeException ex) {
                close();
            } finally {
                queued.set(false);
                if (!stopped.get() && (pending.get() != null || beat.get())) schedule();
            }
        }

        @Override
        public void close() {
            if (stopped.compareAndSet(false, true)) {
                pending.set(null);
                clients.remove(this);
                onClose.run();
            }
        }
    }
}
