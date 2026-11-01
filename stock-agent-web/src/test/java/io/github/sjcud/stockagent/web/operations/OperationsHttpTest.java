package io.github.sjcud.stockagent.web.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sjcud.stockagent.web.StockAgentWebApplication;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.*;

/** 验证真实HTTP健康契约及管理端点暴露范围，不依赖任何外部服务。 */
public class OperationsHttpTest {
    @Test
    public void shouldExposeOnlySafeHealthEndpoints() throws Exception {
        try (ServletWebServerApplicationContext context =
                (ServletWebServerApplicationContext)
                        SpringApplication.run(
                                StockAgentWebApplication.class,
                                "--server.port=0",
                                "--stock.agent.qdrant.enabled=false",
                                "--stock.agent.mysql.enabled=false")) {
            int port = context.getWebServer().getPort();
            assertEquals(200, status(port, "/actuator/health/liveness"));
            assertEquals(200, status(port, "/actuator/health/readiness"));
            assertEquals(404, status(port, "/actuator/env"));
            assertEquals(404, status(port, "/actuator/beans"));
        }
    }

    /** 有界HTTP请求，始终关闭连接以免测试产生悬挂资源。 */
    private int status(int port, String path) throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(3000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
