package io.github.sjcud.stockagent.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 验证 SAMPLE 数据源稳定返回固定数据并拒绝未来日期。 */
public class SampleStockProviderTest {

    @Test
    public void shouldReturnDeterministicSnapshot() {
        SampleStockProvider provider = new SampleStockProvider();

        MarketSnapshot snapshot = provider.snapshot("600519", "2026-10-30");

        assertEquals("600519", snapshot.getSymbol());
        assertEquals("1680.00", snapshot.getClose());
        assertEquals("SAMPLE", snapshot.getSource());
    }

    @Test
    public void shouldRejectDateAfterSampleCutoff() {
        final SampleStockProvider provider = new SampleStockProvider();

        assertThrows(IllegalArgumentException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                provider.snapshot("600519", "2099-01-01");
            }
        });
    }
}
