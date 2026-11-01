package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按显式模式选择提供器；只创建策略，不在启动阶段发网络请求。 */
@Configuration
public class MarketDataProviderConfiguration {
    /** 兼容嵌入式测试和旧调用方，使用默认有界缓存。 */
    public StockDataProvider stockDataProvider(String mode, int connectTimeout, int readTimeout, int perMinute) {
        return stockDataProvider(mode, connectTimeout, readTimeout, perMinute, 64, 300L);
    }

    /** 默认 SAMPLE；LIVE 失败不回退样例。每分钟频率由一个共享装饰器约束。 */
    @Bean
    public StockDataProvider stockDataProvider(
            @Value("${stock.agent.data.mode:SAMPLE}") String mode,
            @Value("${stock.agent.data.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${stock.agent.data.read-timeout-ms:5000}") int readTimeout,
            @Value("${stock.agent.data.requests-per-minute:60}") int perMinute,
            @Value("${stock.agent.data.cache-entries:64}") int cacheEntries,
            @Value("${stock.agent.data.cache-ttl-seconds:300}") long cacheTtlSeconds) {
        DataMode selected = DataMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        if (selected == DataMode.SAMPLE) { return new SampleStockProvider(); }
        Clock clock = Clock.systemUTC();
        return new LiveStockDataProvider(new RateLimitedHttpClient(
                new ApacheHttpTextClient(connectTimeout,readTimeout,1048576),perMinute,1500), clock,
                new PointInTimeDataCache(cacheEntries, Duration.ofSeconds(cacheTtlSeconds), clock));
    }
}
