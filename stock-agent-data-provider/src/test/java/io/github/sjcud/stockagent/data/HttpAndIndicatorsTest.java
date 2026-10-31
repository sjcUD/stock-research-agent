package io.github.sjcud.stockagent.data;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 在本地 HTTP 服务上验证传输边界，并用独立数学结果校验指标。 */
public class HttpAndIndicatorsTest {
    @Test
    public void shouldRejectLargeBodyNon200AndSlowResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes("UTF-8");
            exchange.sendResponseHeaders(200,body.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/error", exchange -> { exchange.sendResponseHeaders(503,-1); exchange.close(); });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(1500); exchange.sendResponseHeaders(200,-1); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start(); String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertTrue(new ApacheHttpTextClient(300,300,100).get(base + "/ok").contains("ok"));
            assertThrows(IOException.class, () -> new ApacheHttpTextClient(300,300,2).get(base + "/ok"));
            assertThrows(IOException.class, () -> new ApacheHttpTextClient(300,300,100).get(base + "/error"));
            assertThrows(SocketTimeoutException.class, () -> new ApacheHttpTextClient(100,100,100).get(base + "/slow"));
        } finally { server.stop(0); workers.shutdownNow(); }
    }

    @Test
    public void shouldAbortSlowHeadersAtTotalDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ExecutorService peers = Executors.newSingleThreadExecutor();
            Future<?> peer = peers.submit(() -> {
                try (Socket socket = server.accept()) {
                    OutputStream output = socket.getOutputStream();
                    for (byte value : "HTTP/1.1 200 OK\r\nX-Slow: abcdefghijklmnopqrstuvwxyz\r\nContent-Length: 0\r\n\r\n".getBytes("UTF-8")) {
                        output.write(value); output.flush(); Thread.sleep(30);
                    }
                } catch (Exception ignored) { /* 客户端主动关闭即为此测试预期。 */ }
            });
            long start = System.nanoTime();
            try {
                assertThrows(IOException.class, () -> new ApacheHttpTextClient(100,100,100)
                        .get("http://127.0.0.1:" + server.getLocalPort()));
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000,
                        "总截止时间必须覆盖慢响应头");
            } finally { peer.cancel(true); peers.shutdownNow(); }
        }
    }

    @Test
    public void shouldCancelRequestDuringHeaderReadAndReleaseCaller() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1), finished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            ExecutorService peers = Executors.newSingleThreadExecutor();
            Future<?> peer = peers.submit(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown(); Thread.sleep(5000);
                } catch (Exception ignored) { /* 测试结束后清理服务端连接。 */ }
            });
            Thread caller = new Thread(() -> {
                try { new ApacheHttpTextClient(3000,3000,100).get("http://127.0.0.1:" + server.getLocalPort()); }
                catch (Throwable exception) { failure.set(exception); }
                finally { finished.countDown(); }
            });
            try {
                caller.start(); assertTrue(accepted.await(2,TimeUnit.SECONDS)); caller.interrupt();
                assertTrue(finished.await(1,TimeUnit.SECONDS),"执行中取消应及时释放调用线程");
                assertTrue(failure.get() instanceof InterruptedIOException);
            } finally { caller.interrupt(); caller.join(1000); peer.cancel(true); peers.shutdownNow(); }
        }
    }

    @Test
    public void shouldRefuseExcessRequestsBeforeCallingTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpTextClient client = new RateLimitedHttpClient(url -> { calls.incrementAndGet(); return "{}"; },1,0);
        client.get("https://example.com");
        assertThrows(IOException.class, () -> client.get("https://example.com"));
        assertEquals(1,calls.get());
    }

    @Test
    public void shouldCalculateSmaRsiAndMacdForFlatAndIncreasingSeries() {
        List<DailyBar> flat = new ArrayList<DailyBar>(), rising = new ArrayList<DailyBar>();
        for (int i = 0; i < 40; i++) {
            String date = LocalDate.of(2026,9,1).plusDays(i).toString();
            flat.add(new DailyBar(date,"10","10","10","10"));
            String close = Integer.toString(10+i); rising.add(new DailyBar(date,close,close,close,close));
        }
        Map<String,BigDecimal> f = TechnicalIndicators.calculate(flat), r = TechnicalIndicators.calculate(rising);
        assertEquals(0, f.get("SMA20").compareTo(BigDecimal.TEN));
        assertEquals(0, f.get("RSI14").compareTo(new BigDecimal("50")));
        assertEquals(0, f.get("MACD_HIST").compareTo(BigDecimal.ZERO));
        assertEquals(0, r.get("SMA5").compareTo(new BigDecimal("47")));
        assertEquals(0, r.get("RSI14").compareTo(new BigDecimal("100")));
        assertTrue(TechnicalIndicators.calculate(flat.subList(0,4)).isEmpty());
    }
    @Test
    public void shouldMatchIndependentMixedRsiAndNonzeroMacdValue() {
        String[] closes = {"44.34","44.09","44.15","43.61","44.33","44.83","45.10","45.42","45.84","46.08","45.89","46.03","45.61","46.28","46.28"};
        List<DailyBar> bars = new ArrayList<DailyBar>();
        for (int i=0;i<closes.length;i++) {
            String value=closes[i]; bars.add(new DailyBar(LocalDate.of(2026,9,1).plusDays(i).toString(),value,value,value,value));
        }
        assertEquals(70.464135,TechnicalIndicators.calculate(bars).get("RSI14").doubleValue(),0.000001);
        bars.clear();
        for (int i=0;i<40;i++) {
            String value=Integer.toString(10+i); bars.add(new DailyBar(LocalDate.of(2026,9,1).plusDays(i).toString(),value,value,value,value));
        }
        // 等差价格下 EMA 的闭式解，独立于实现中的迭代过程。
        double expected = 12.5*(1-Math.pow(25.0/27,39)) - 5.5*(1-Math.pow(11.0/13,39));
        assertEquals(expected,TechnicalIndicators.calculate(bars).get("MACD_DIF").doubleValue(),0.000001);
    }
}
