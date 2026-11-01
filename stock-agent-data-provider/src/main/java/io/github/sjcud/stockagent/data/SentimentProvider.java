package io.github.sjcud.stockagent.data;

import java.time.LocalDate;

/** 社交情绪供应商边界；样本数和分数必须来自可追溯响应。 */
public interface SentimentProvider {
    DataFeed<SentimentSnapshot> load(String symbol, LocalDate asOf);
}
