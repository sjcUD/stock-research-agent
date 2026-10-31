package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.*;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 按显式模式选择提供器；只创建策略，不在启动阶段发网络请求。 */
@Configuration
public class MarketDataProviderConfiguration {
    /** 默认 SAMPLE；LIVE 失败不回退样例。每分钟频率由一个共享装饰器约束。 */
    @Bean
    public StockDataProvider stockDataProvider(
            @Value("${stock.agent.data.mode:SAMPLE}") String mode,
            @Value("${stock.agent.data.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${stock.agent.data.read-timeout-ms:5000}") int readTimeout,
            @Value("${stock.agent.data.requests-per-minute:60}") int perMinute) {
        DataMode selected = DataMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        if (selected == DataMode.SAMPLE) { return new SampleStockProvider(); }
        return new LiveStockDataProvider(new RateLimitedHttpClient(
                new ApacheHttpTextClient(connectTimeout,readTimeout,1048576),perMinute,1500),Clock.systemUTC());
    }
}
