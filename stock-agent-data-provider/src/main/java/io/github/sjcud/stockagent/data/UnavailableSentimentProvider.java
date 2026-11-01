package io.github.sjcud.stockagent.data;
import java.time.LocalDate; import java.util.Collections;
/** 未配置授权时的显式不可用实现。 */
public final class UnavailableSentimentProvider implements SentimentProvider {
 public DataFeed<SentimentSnapshot> load(String symbol, LocalDate asOf){return new DataFeed<SentimentSnapshot>("UNCONFIGURED","","",DataStatus.UNAVAILABLE,true,"SOCIAL_SENTIMENT_NOT_CONFIGURED",Collections.<SentimentSnapshot>emptyList());}
}
