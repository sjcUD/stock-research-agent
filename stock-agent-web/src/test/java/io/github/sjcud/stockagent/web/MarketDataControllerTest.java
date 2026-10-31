package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.research.MultiResearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;

/** 验证无需用户报价的 API、数据模式选择与错误状态。 */
public class MarketDataControllerTest {
    @Test
    public void shouldReturnSampleDataWithoutModelAndRejectInvalidRequest() {
        MarketDataController controller = new MarketDataController(new SampleStockProvider(),
                new StaticListableBeanFactory().getBeanProvider(MultiResearchService.class));
        assertEquals(DataMode.SAMPLE,controller.data("600519","2026-10-30").getMode());
        assertEquals(400,assertThrows(ResponseStatusException.class,
                () -> controller.data("000001","2026-10-30")).getStatusCode().value());
        MarketDataController.MarketRequest request = new MarketDataController.MarketRequest();
        request.setQuestion("研究一下"); request.setSymbol("600519"); request.setAsOf("2026-10-30");
        assertEquals(503,assertThrows(ResponseStatusException.class,
                () -> controller.research(request)).getStatusCode().value());
    }

    @Test
    public void shouldFailClosedInsteadOfReturningSampleForLiveErrors() {
        StockDataProvider unavailable = (symbol,date) -> { throw new DataAccessException("上游不可用",null); };
        MarketDataController controller = new MarketDataController(unavailable,
                new StaticListableBeanFactory().getBeanProvider(MultiResearchService.class));
        assertEquals(502,assertThrows(ResponseStatusException.class,
                () -> controller.data("600519","2026-10-30")).getStatusCode().value());
    }

    @Test
    public void shouldSelectModeWithoutCallingNetworkDuringConfiguration() {
        MarketDataProviderConfiguration configuration = new MarketDataProviderConfiguration();
        assertTrue(configuration.stockDataProvider("SAMPLE",200,200,60) instanceof SampleStockProvider);
        assertTrue(configuration.stockDataProvider("LIVE",200,200,60) instanceof LiveStockDataProvider);
        assertThrows(IllegalArgumentException.class, () -> configuration.stockDataProvider("invalid",200,200,60));
    }
}
