package io.github.sjcud.stockagent.data;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界 point-in-time 结果缓存：键包含证券、研究日和复权口径，禁止把较新抓取结果覆盖到旧研究日。
 *
 * <p>缓存只保存完整成功结果，容量和TTL均由装配层控制；它不是数据库，也不替代来源指纹。</p>
 */
public final class PointInTimeDataCache {
    private final int maxEntries;
    private final long ttlMillis;
    private final Clock clock;
    private final Map<String, Entry> entries;

    /** 创建线程安全的LRU缓存。 */
    public PointInTimeDataCache(int maxEntries, Duration ttl, Clock clock) {
        if (maxEntries < 1 || ttl == null || ttl.isNegative() || ttl.isZero() || clock == null)
            throw new IllegalArgumentException("point-in-time缓存配置无效");
        this.maxEntries = maxEntries; this.ttlMillis = ttl.toMillis(); this.clock = clock;
        this.entries = new LinkedHashMap<String, Entry>(16, 0.75f, true);
    }
    /** 读取仍在TTL内的同一研究口径结果。 */
    public synchronized StockResearchData get(String symbol, String asOf, PriceAdjustmentMode mode) {
        String key = key(symbol, asOf, mode); Entry entry = entries.get(key);
        if (entry == null) return null;
        if (clock.millis() - entry.createdAt > ttlMillis) { entries.remove(key); return null; }
        return entry.value;
    }
    /** 只缓存成功且完整的结果，不接受空值。 */
    public synchronized void put(String symbol, String asOf, PriceAdjustmentMode mode, StockResearchData value) {
        if (value == null) return;
        entries.put(key(symbol, asOf, mode), new Entry(clock.millis(), value));
        while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next());
    }
    /** 测试和运维使用的当前条目数。 */
    public synchronized int size() { return entries.size(); }
    private String key(String symbol, String asOf, PriceAdjustmentMode mode) {
        return symbol + "\u0000" + asOf + "\u0000" + (mode == null ? PriceAdjustmentMode.UNADJUSTED : mode).name();
    }
    private static final class Entry {
        private final long createdAt; private final StockResearchData value;
        private Entry(long createdAt, StockResearchData value) { this.createdAt = createdAt; this.value = value; }
    }
}
