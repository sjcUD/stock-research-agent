package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import io.github.sjcud.stockagent.runtime.harness.*;

/** 真实本地HTTP验证响应头、正文和阶段截止，避免仅测试配置属性。 */
public class ModelTransportTest {
    @Test public void shouldBoundSlowHeadersAndSlowBody() throws Exception {
        for(boolean headers:new boolean[]{true,false}) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            ExecutorService workers=Executors.newCachedThreadPool();server.setExecutor(workers);
            server.createContext("/",exchange->{try{
                if(headers)Thread.sleep(2000);
                exchange.sendResponseHeaders(200,0);
                if(!headers){exchange.getResponseBody().write('a');exchange.getResponseBody().flush();Thread.sleep(2000);}
                exchange.getResponseBody().write('b');
            }catch(Exception ignored){}finally{exchange.close();}});server.start();
            try(ModelHttpTransport transport=new ModelHttpTransport(300,150)) {
                RestClient client=RestClient.builder().requestFactory(transport).build();long started=System.nanoTime();
                assertThrows(RuntimeException.class,()->client.get().uri("http://127.0.0.1:"+server.getAddress().getPort()).retrieve().body(String.class));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1800);
            }finally{server.stop(0);workers.shutdownNow();}
        }
    }
    @Test public void shouldHonorStageDeadlineAndClearBinding() throws Exception {
        try(ModelHttpTransport transport=new ModelHttpTransport(500,5000)) {
            try(CallDeadline.Scope ignored=CallDeadline.bind(40)){assertTrue(transport.effectiveReadTimeoutMillis()<=40);}
            assertEquals(5000,transport.effectiveReadTimeoutMillis());
        }
    }
    @Test public void shouldClassify429WithoutLeakingProviderBody() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            String body="private-provider-body";
            exchange.getResponseHeaders().set("Content-Type","application/json");byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try(ModelHttpTransport transport=new ModelHttpTransport(500,2000)) {
            assertEquals(FailureClass.RETRYABLE, new FailureClassifier().classify(429));
            RestClient client=RestClient.builder().requestFactory(transport).build();
            assertThrows(RuntimeException.class,()->client.get().uri("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions").retrieve().body(String.class));
        }finally{server.stop(0);}
    }
}
