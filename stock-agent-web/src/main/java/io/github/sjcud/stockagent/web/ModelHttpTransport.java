package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.runtime.harness.CallDeadline;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * 模型 HTTP 传输适配器。
 *
 * <p>每次创建请求时都读取当前阶段的剩余截止时间，并把它与供应商配置的最大正文读取
 * 超时取较小值。这样模型调用不会因为固定的全局 read timeout 越过阶段预算。</p>
 */
public final class ModelHttpTransport implements ClientHttpRequestFactory, AutoCloseable {
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    /** 创建带有连接和响应正文上限的传输工厂。 */
    public ModelHttpTransport(int connectTimeoutMillis, int readTimeoutMillis) {
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("模型 HTTP 超时必须大于0");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    /** 根据当前 Harness 阶段截止时间计算本次请求的正文读取上限。 */
    public int effectiveReadTimeoutMillis() {
        long bounded = CallDeadline.boundedMillis(readTimeoutMillis);
        return (int) Math.max(1L, Math.min((long) readTimeoutMillis, bounded));
    }

    /** 为每个请求创建 JDK 客户端，避免不同并发阶段共享可变超时。 */
    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory delegate = new JdkClientHttpRequestFactory(client);
        delegate.setReadTimeout(Duration.ofMillis(effectiveReadTimeoutMillis()));
        return delegate.createRequest(uri, httpMethod);
    }

    /** JDK HttpClient 没有需要显式关闭的资源；保留 AutoCloseable 便于测试和容器生命周期管理。 */
    @Override
    public void close() {
        // HttpClient 由 JDK 管理连接池生命周期，无需强制关闭。
    }
}
