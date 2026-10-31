package io.github.sjcud.stockagent.rag.store;

import io.github.sjcud.stockagent.rag.retrieval.RetrievalHit;
import io.github.sjcud.stockagent.rag.retrieval.RetrievalQuery;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 为 VectorStore 提供有界检索和取消释放。 */
public class TimedVectorStore {
    private final VectorStore delegate;

    public TimedVectorStore(VectorStore delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("VectorStore 不能为空");
        }
        this.delegate = delegate;
    }

    /** 超时或取消时取消 Future 并关闭执行线程。 */
    public List<RetrievalHit> search(RetrievalQuery query, long timeoutMillis,
                                     BooleanSupplier cancelled) throws Exception {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis 必须大于0");
        }
        if (cancelled.getAsBoolean()) {
            throw new IllegalStateException("检索任务已取消");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<RetrievalHit>> future = executor.submit(new Callable<List<RetrievalHit>>() {
            @Override
            public List<RetrievalHit> call() {
                return delegate.search(query);
            }
        });
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            future.cancel(true);
            throw exception;
        } finally {
            executor.shutdownNow();
        }
    }
}
