package io.github.sjcud.stockagent.data;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证供应商解析、来源、时点、数据质量与部分失败降级。 */
public class MarketDataProviderTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private String bars = "{\"code\":0,\"data\":{\"sh600519\":{\"day\":[[\"2026-10-29\",\"10\",\"11\",\"12\",\"9\",\"100\"],[\"2026-10-30\",\"11\",\"12\",\"13\",\"10\",\"100\"],[\"2026-11-02\",\"12\",\"13\",\"14\",\"11\",\"100\"]]}}}";
    private String news = "p9callback({\"code\":0,\"result\":{\"cmsArticleWebOld\":[{\"code\":\"n1\",\"date\":\"2026-10-30 18:00:00\",\"title\":\"600519新闻\",\"content\":\"样例内容\",\"url\":\"https://example.com/n1\",\"mediaName\":\"样例媒体\"},{\"code\":\"future\",\"date\":\"2026-10-31 18:00:00\",\"title\":\"未来新闻\",\"url\":\"https://example.com/future\"}]}})";
    private String announcements = "{\"success\":1,\"data\":{\"list\":[{\"art_code\":\"a1\",\"notice_date\":\"2026-10-30 00:00:00\",\"display_time\":\"2026-10-29 18:00:00:000\",\"title\":\"600519公告\",\"codes\":[{\"stock_code\":\"600519\"}]}]}}";

    @Test
    public void shouldLoadOnlyDataAvailableByResearchDate() {
        StockResearchData data = provider(url -> response(url)).load("600519", "2026-10-30");
        assertEquals(DataMode.LIVE, data.getMode());
        assertEquals("12", data.getSnapshot().getClose());
        assertEquals(2, data.getHistory().getItems().size());
        assertEquals(1, data.getNews().getItems().size());
        assertEquals(1, data.getAnnouncements().getItems().size());
        assertEquals("2026-10-30", data.getAnnouncements().getItems().get(0).getPublishedAt().substring(0,10));
        assertTrue(data.getHistory().getVersion().startsWith("sha256:"));
        assertTrue(data.getNews().isLimitedHistory());
        assertThrows(UnsupportedOperationException.class, () -> data.getHistory().getItems().clear());
    }

    @Test
    public void shouldExposeOptionalFailuresWithoutChangingLiveMode() {
        StockResearchData data = provider(url -> {
            if (!url.contains("gtimg")) { throw new java.io.IOException("不可用"); }
            return bars;
        }).load("sh600519", "2026-10-30");
        assertEquals(DataStatus.UNAVAILABLE, data.getNews().getStatus());
        assertEquals(DataStatus.UNAVAILABLE, data.getAnnouncements().getStatus());
        assertEquals(DataMode.LIVE, data.getMode());
    }

    @Test
    public void shouldFailForInvalidQuotesAndNotFallBackToSample() {
        bars = bars.replace("\"13\",\"10\"", "\"1\",\"10\"");
        assertThrows(DataAccessException.class, () -> provider(url -> response(url)).load("600519", "2026-10-30"));
        assertThrows(DataAccessException.class, () -> provider(url -> { throw new java.io.IOException(); })
                .load("600519", "2026-10-30"));
    }

    @Test
    public void shouldRejectInvalidIdentityAndFutureDateBeforeNetwork() {
        AtomicInteger calls = new AtomicInteger();
        LiveStockDataProvider provider = provider(url -> { calls.incrementAndGet(); return response(url); });
        assertThrows(IllegalArgumentException.class, () -> provider.load("sh000001", "2026-10-30"));
        assertThrows(IllegalArgumentException.class, () -> provider.load("600519", "2026-11-02"));
        assertEquals(0, calls.get());
    }

    @Test
    public void shouldRestrictSampleToDocumentedFixture() {
        assertEquals(DataMode.SAMPLE, new SampleStockProvider().load("600519", "2026-10-30").getMode());
        assertThrows(IllegalArgumentException.class, () -> new SampleStockProvider().load("000001", "2026-10-30"));
        assertThrows(IllegalArgumentException.class, () -> new SampleStockProvider().load("600519", "2020-01-01"));
    }

    @Test
    public void shouldRejectUnboundedNumericNotationBeforeDecimalExpansion() {
        assertThrows(IllegalArgumentException.class, () -> new DailyBar("2026-10-30",
                "1e1000000000","1e1000000000","1e1000000000","1e1000000000"));
    }

    private LiveStockDataProvider provider(HttpTextClient client) { return new LiveStockDataProvider(client, clock); }
    private String response(String url) {
        if (url.contains("gtimg")) { return bars; }
        if (url.contains("anotice")) { return announcements; }
        return news;
    }
}
