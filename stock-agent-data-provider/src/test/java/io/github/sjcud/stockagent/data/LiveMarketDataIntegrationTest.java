package io.github.sjcud.stockagent.data;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** 可选真实市场数据测试，不需要模型或数据库，不将固定抓取数据提交仓库。 */
public class LiveMarketDataIntegrationTest {
    @Test
    public void shouldLoadRealDailyBarsAndExposeOptionalFeedStatus() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MARKET_DATA_INTEGRATION")));
        String date = System.getenv("MARKET_DATA_AS_OF");
        if (date == null || date.trim().isEmpty()) { date = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString(); }
        StockResearchData data = new LiveStockDataProvider(new RateLimitedHttpClient(
                new ApacheHttpTextClient(3000,5000,1048576),60,1500),Clock.systemUTC()).load("600519",date);
        assertEquals(DataMode.LIVE,data.getMode());
        assertTrue(data.getHistory().getItems().size() >= 35);
        assertFalse(data.getSnapshot().getAsOf().compareTo(date) > 0);
        assertTrue(data.getIndicators().containsKey("MACD_DIF"));
        assertTrue(data.getHistory().getVersion().startsWith("sha256:"));
        assertNotNull(data.getNews().getStatus());
        assertNotNull(data.getAnnouncements().getStatus());
        for (MarketArticle item : data.getNews().getItems()) {
            assertTrue(item.getPublishedAt().substring(0,10).compareTo(date) <= 0);
            assertFalse(item.getUrl().isEmpty());
        }
    }
}
