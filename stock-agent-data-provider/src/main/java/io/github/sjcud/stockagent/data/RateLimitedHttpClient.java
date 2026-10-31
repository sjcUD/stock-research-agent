package io.github.sjcud.stockagent.data;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

/** 装饰器：单进程共享发送时隙，超过最大排队时间快速失败，不创建后台线程。 */
public class RateLimitedHttpClient implements HttpTextClient {
    /** 实际传输适配器。 */
    private final HttpTextClient delegate;
    /** 相邻请求的最小纳秒间隔。 */
    private final long intervalNanos;
    /** 最大排队时间。 */
    private final long maxWaitNanos;
    /** 下一个允许发送时刻，所有访问由对象锁保护。 */
    private long nextSlot;
    /** 设置每分钟频次及单次允许的最长等待。 */
    public RateLimitedHttpClient(HttpTextClient delegate, int perMinute, long maxWaitMillis) {
        if (delegate == null || perMinute <= 0 || maxWaitMillis < 0) { throw new IllegalArgumentException("限流配置无效"); }
        this.delegate = delegate; this.intervalNanos = TimeUnit.MINUTES.toNanos(1) / perMinute;
        this.maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMillis);
        this.nextSlot = System.nanoTime();
    }
    /** 先预留时隙，再在锁外等待，避免慢请求持有全局锁。 */
    @Override public String get(String url) throws IOException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            wait = Math.max(0, nextSlot - now);
            if (wait > maxWaitNanos) { throw new IOException("DATA_RATE_LIMITED"); }
            nextSlot = now + wait + intervalNanos;
        }
        try { TimeUnit.NANOSECONDS.sleep(wait); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("DATA_CANCELLED");
        }
        return delegate.get(url);
    }
}
