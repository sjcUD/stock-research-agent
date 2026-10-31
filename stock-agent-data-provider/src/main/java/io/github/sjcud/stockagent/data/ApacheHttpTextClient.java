package io.github.sjcud.stockagent.data;

import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

/** Java8 HTTP 适配器：总截止时间覆盖连接、响应头和正文，并支持执行中取消。 */
public class ApacheHttpTextClient implements HttpTextClient {
    /** 连接超时毫秒。 */
    private final int connectTimeout;
    /** 单次读取空闲超时毫秒。 */
    private final int readTimeout;
    /** 最大响应字节数，压缩自动解码关闭以保持清晰的大小限制。 */
    private final int maxBytes;
    /** 配置正数时限和响应上限。 */
    public ApacheHttpTextClient(int connectTimeout, int readTimeout, int maxBytes) {
        if (connectTimeout <= 0 || readTimeout <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("HTTP 限制必须为正数");
        }
        this.connectTimeout = connectTimeout; this.readTimeout = readTimeout; this.maxBytes = maxBytes;
    }
    /** 发起一次只读请求，总网络时限为连接时限加读取时限，到期主动取消底层请求。 */
    @Override public String get(String url) throws IOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("DATA_CANCELLED"); }
        HttpGet request = new HttpGet(url);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connectTimeout,TimeUnit.MILLISECONDS)
                .setConnectionRequestTimeout(connectTimeout,TimeUnit.MILLISECONDS)
                .setResponseTimeout(readTimeout,TimeUnit.MILLISECONDS)
                .setHardCancellationEnabled(true).build();
        AtomicBoolean expired = new AtomicBoolean(false), cancelled = new AtomicBoolean(false);
        Thread caller = Thread.currentThread();
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action,"stock-data-deadline"); thread.setDaemon(true); return thread;
        });
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config)
                .disableAutomaticRetries().disableRedirectHandling().disableContentCompression().build()) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos((long) connectTimeout + readTimeout);
            // 不能只在 read 循环检查：响应头持续滴流也必须到期关闭连接。
            watcher.scheduleWithFixedDelay(() -> {
                if (caller.isInterrupted()) { cancelled.set(true); request.cancel(); }
                else if (System.nanoTime() >= deadline) { expired.set(true); request.cancel(); }
            },10,10,TimeUnit.MILLISECONDS);
            request.setHeader("User-Agent","StockResearchAgent-Learning/0.1");
            request.setHeader("Accept-Encoding","identity");
            return client.execute(request,response -> {
                if (response.getCode() != 200) { throw new IOException("UPSTREAM_HTTP_ERROR"); }
                if (response.getEntity() == null) { return ""; }
                if (response.getEntity().getContentLength() > maxBytes) { throw new IOException("RESPONSE_TOO_LARGE"); }
                try (InputStream input = response.getEntity().getContent(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    for (int size; (size = input.read(buffer)) != -1;) {
                        if (caller.isInterrupted()) { throw new InterruptedIOException("DATA_CANCELLED"); }
                        if (System.nanoTime() >= deadline) { throw new SocketTimeoutException("DATA_DEADLINE_EXCEEDED"); }
                        if ((long) output.size() + size > maxBytes) { throw new IOException("RESPONSE_TOO_LARGE"); }
                        output.write(buffer,0,size);
                    }
                    return new String(output.toByteArray(),StandardCharsets.UTF_8);
                }
            });
        } catch (IOException exception) {
            if (cancelled.get() || caller.isInterrupted()) {
                InterruptedIOException failure = new InterruptedIOException("DATA_CANCELLED"); failure.initCause(exception); throw failure;
            }
            if (expired.get()) {
                SocketTimeoutException failure = new SocketTimeoutException("DATA_DEADLINE_EXCEEDED"); failure.initCause(exception); throw failure;
            }
            throw exception;
        } finally {
            request.cancel(); watcher.shutdownNow();
        }
    }
}
