package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** 启动随机本地端口验证真正的 MVC 参数绑定，不依赖编译器参数名反射。 */
public class MarketHttpBindingTest {
    @Test
    public void shouldBindSymbolAndAsOfThroughHttp() throws Exception {
        try (ServletWebServerApplicationContext context = (ServletWebServerApplicationContext)
                SpringApplication.run(StockAgentWebApplication.class,"--server.port=0","--server.shutdown=immediate",
                        "--stock.agent.chat.api-key=","--stock.agent.qdrant.enabled=false","--stock.agent.data.mode=SAMPLE")) {
            int port = context.getWebServer().getPort();
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                    + "/api/market/data?symbol=600519&asOf=2026-10-30").openConnection();
            try {
                assertEquals(200,connection.getResponseCode());
                try (java.io.InputStream input = connection.getInputStream()) {
                    assertEquals("SAMPLE",new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).path("mode").asText());
                }
            }
            finally { connection.disconnect(); }
        }
    }
}
