package io.github.sjcud.stockagent.data;

import com.fasterxml.jackson.databind.*; import java.time.*; import java.util.*;
/** 通用 JSON 情绪网关适配器，支持 StockTwits/Reddit 聚合服务的统一响应字段。 */
public final class JsonSentimentProvider implements SentimentProvider {
 private final HttpTextClient client; private final String urlTemplate; private final ObjectMapper json=new ObjectMapper();
 public JsonSentimentProvider(HttpTextClient client,String urlTemplate){if(client==null||urlTemplate==null||!urlTemplate.contains("%s"))throw new IllegalArgumentException("情绪接口配置无效");this.client=client;this.urlTemplate=urlTemplate;}
 public DataFeed<SentimentSnapshot> load(String symbol,LocalDate asOf){try{String raw=client.get(String.format(Locale.ROOT,urlTemplate,java.net.URLEncoder.encode(symbol,"UTF-8")));JsonNode root=json.readTree(raw);String date=root.path("asOf").asText(asOf.toString());if(LocalDate.parse(date).isAfter(asOf))return unavailable("SENTIMENT_AFTER_CUTOFF");SentimentSnapshot value=new SentimentSnapshot(date,root.path("score").asText("0"),root.path("sampleCount").asInt(0));return new DataFeed<SentimentSnapshot>(urlTemplate,"sha256:"+Integer.toHexString(raw.hashCode()),Instant.now().toString(),DataStatus.AVAILABLE,true,"POINT_IN_TIME_AGGREGATED",Collections.singletonList(value));}catch(Exception e){return unavailable("UPSTREAM_OR_SCHEMA_UNAVAILABLE");}}
 private DataFeed<SentimentSnapshot> unavailable(String detail){return new DataFeed<SentimentSnapshot>(urlTemplate,"",Instant.now().toString(),DataStatus.UNAVAILABLE,true,detail,Collections.<SentimentSnapshot>emptyList());}
}
