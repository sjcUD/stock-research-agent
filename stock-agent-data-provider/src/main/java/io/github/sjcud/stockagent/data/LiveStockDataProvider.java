package io.github.sjcud.stockagent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Java HTTP 在线适配：腾讯未复权日线、东方财富最近新闻与公告列表。
 * 网页数据协议可能变化；资讯只覆盖最近窗口，不承诺完整历史、财报全文或社交情绪覆盖。
 */
public class LiveStockDataProvider implements StockDataProvider {
    /** 腾讯日线端点，只读取 day，不读取响应内的当前实时 qt 快照。 */
    private static final String QUOTES = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    /** 东方财富关键词新闻端点。 */
    private static final String NEWS = "https://search-api-web.eastmoney.com/search/jsonp";
    /** 东方财富公告列表端点，不在本阶段下载公告全文。 */
    private static final String ANNOUNCEMENTS = "https://np-anotice-stock.eastmoney.com/api/security/ann";
    /** HTTP 与限流由可替换接口负责。 */
    private final HttpTextClient client;
    /** 注入时钟以确定性验证截止日期。 */
    private final Clock clock;
    /** 只缓存完整 point-in-time 结果，避免同一研究日重复请求供应商。 */
    private final PointInTimeDataCache cache;
    /** 可选公告全文策略；默认不下载正文，列表状态仍然保留。 */
    private final AnnouncementTextProvider announcementTextProvider;
    /** JSON 解析器在构造后不修改配置，允许并发读取。 */
    private final ObjectMapper json = new ObjectMapper();
    /** 注入网络适配器与时钟。 */
    public LiveStockDataProvider(HttpTextClient client, Clock clock) {
        this(client, clock, new PointInTimeDataCache(64, java.time.Duration.ofMinutes(5), clock), article -> new AnnouncementTextProvider.FullText("", DataStatus.UNAVAILABLE));
    }
    /** 注入缓存策略，测试可使用短TTL或禁用式单条缓存。 */
    public LiveStockDataProvider(HttpTextClient client, Clock clock, PointInTimeDataCache cache) {
        this(client, clock, cache, article -> new AnnouncementTextProvider.FullText("", DataStatus.UNAVAILABLE));
    }
    /** 注入公告全文适配器，列表和全文可以独立降级。 */
    public LiveStockDataProvider(HttpTextClient client, Clock clock, PointInTimeDataCache cache, AnnouncementTextProvider textProvider) {
        if (client == null || clock == null || cache == null || textProvider == null) { throw new IllegalArgumentException("取数依赖不能为空"); }
        this.client = client; this.clock = clock.withZone(ZoneId.of("Asia/Shanghai")); this.cache = cache; this.announcementTextProvider = textProvider;
    }
    /** 加载按日终截止的数据；核心行情失败中止，辅助资讯失败显式降级。 */
    @Override public StockResearchData load(String symbol, String asOf) {
        return load(symbol, asOf, PriceAdjustmentMode.UNADJUSTED);
    }
    /** 加载供应商复权日线，并用同一截止窗口的未复权序列推导公司行为因子。 */
    @Override public StockResearchData load(String symbol, String asOf, PriceAdjustmentMode adjustment) {
        String normalized = normalizeSymbol(symbol);
        LocalDate cutoff = LocalDate.parse(asOf);
        LocalDate today = LocalDate.now(clock);
        if (cutoff.isAfter(today) || (cutoff.equals(today) && LocalTime.now(clock).isBefore(LocalTime.of(16, 0)))) {
            throw new IllegalArgumentException("只支持已经结束并完成日线更新的日终研究日期");
        }
        PriceAdjustmentMode selected = adjustment == null ? PriceAdjustmentMode.UNADJUSTED : adjustment;
        StockResearchData cached = cache.get(normalized, asOf, selected);
        if (cached != null) return cached;
        DataFeed<DailyBar> history;
        try { history = history(normalized, cutoff, selected); }
        catch (Exception exception) { throw new DataAccessException("LIVE 行情不可用，未回退 SAMPLE", exception); }
        DailyBar last = history.getItems().get(history.getItems().size() - 1);
        if (LocalDate.parse(last.getDate()).isBefore(cutoff.minusDays(14))) {
            throw new DataAccessException("最近行情超过14天，请核查停牌或数据时效", null);
        }
        MarketSnapshot snapshot = new MarketSnapshot(normalized, last.getClose().toPlainString(), last.getDate(), "TENCENT_DAILY_" + selected.name());
        List<CorporateAction> actions = Collections.emptyList();
        if (selected != PriceAdjustmentMode.UNADJUSTED) {
            try {
                DataFeed<DailyBar> raw = history(normalized, cutoff, PriceAdjustmentMode.UNADJUSTED);
                actions = inferActions(raw.getItems(), history.getItems());
            } catch (Exception exception) {
                throw new DataAccessException("复权基准行情不可用，未返回不完整结果", exception);
            }
        }
        StockResearchData result = new StockResearchData(asOf, DataMode.LIVE, snapshot, history,
                articles(normalized, cutoff, false), articles(normalized, cutoff, true),
                TechnicalIndicators.calculate(history.getItems()), selected, actions);
        cache.put(normalized, asOf, selected, result);
        return result;
    }
    /** 规范沪深 A 股身份；显式前后缀必须与代码匹配，首版不接指数、北交所与美股。 */
    public static String normalizeSymbol(String input) {
        if (input == null) { throw new IllegalArgumentException("证券代码不能为空"); }
        String value = input.trim().toLowerCase(Locale.ROOT);
        String prefix = "";
        if (value.startsWith("sh") || value.startsWith("sz")) { prefix = value.substring(0,2); value = value.substring(2); }
        if (value.endsWith(".sh") || value.endsWith(".sz")) {
            String suffix = value.substring(value.length() - 2);
            if (!prefix.isEmpty() && !prefix.equals(suffix)) { throw new IllegalArgumentException("市场前后缀冲突"); }
            prefix = suffix; value = value.substring(0, value.length() - 3);
        }
        if (!value.matches("(60[0-9]{4}|68[0-9]{4}|00[0-9]{4}|30[0-9]{4})")) {
            throw new IllegalArgumentException("首版只支持沪深 A 股六位代码");
        }
        String market = value.startsWith("6") ? "sh" : "sz";
        if (!prefix.isEmpty() && !prefix.equals(market)) { throw new IllegalArgumentException("市场与证券代码不匹配"); }
        return value;
    }
    /** 校验日线结构、剔除未来数据、拒绝重复日期并按交易日升序排列。 */
    private DataFeed<DailyBar> history(String symbol, LocalDate cutoff, PriceAdjustmentMode adjustment) throws Exception {
        String identifier = (symbol.startsWith("6") ? "sh" : "sz") + symbol;
        String series = adjustment == PriceAdjustmentMode.FORWARD_ADJUSTED ? "qfqday"
                : adjustment == PriceAdjustmentMode.BACKWARD_ADJUSTED ? "hfqday" : "day";
        String query = identifier + "," + series + "," + cutoff.minusDays(180) + "," + cutoff + ",180,";
        String raw = client.get(QUOTES + "?param=" + encode(query));
        JsonNode root = json.readTree(raw);
        if (root.path("code").asInt(-1) != 0) { throw new IllegalArgumentException("日线业务状态异常"); }
        JsonNode rows = root.path("data").path(identifier).path(series);
        if (!rows.isArray()) { throw new IllegalArgumentException("缺少未复权日线字段"); }
        List<DailyBar> bars = new ArrayList<DailyBar>(); Set<String> dates = new HashSet<String>();
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 5) { throw new IllegalArgumentException("日线行结构异常"); }
            LocalDate date = LocalDate.parse(row.get(0).asText());
            if (date.isAfter(cutoff) || date.isBefore(cutoff.minusDays(180))) { continue; }
            if (!dates.add(date.toString())) { throw new IllegalArgumentException("重复日线"); }
            String volume = row.size() > 5 ? row.get(5).asText() : null;
            bars.add(new DailyBar(date.toString(), row.get(1).asText(), row.get(2).asText(), row.get(3).asText(), row.get(4).asText(), volume));
        }
        Collections.sort(bars, Comparator.comparing(DailyBar::getDate));
        if (bars.isEmpty()) { throw new IllegalArgumentException("窗口内无有效日线"); }
        return feed(QUOTES, raw, false, "CNY;" + adjustment.name() + ";WINDOW_180_CALENDAR_DAYS;VOLUME_SHARES_WHEN_PRESENT", bars);
    }
    /** 按相同交易日比较复权与未复权收盘价，记录突变因子而不猜测分红/送转类型。 */
    private List<CorporateAction> inferActions(List<DailyBar> raw, List<DailyBar> adjusted) {
        Map<String,DailyBar> before = new HashMap<String,DailyBar>();
        for (DailyBar bar : raw) before.put(bar.getDate(), bar);
        List<CorporateAction> actions = new ArrayList<CorporateAction>();
        java.math.BigDecimal previous = null;
        for (DailyBar bar : adjusted) {
            DailyBar original = before.get(bar.getDate()); if (original == null) continue;
            java.math.BigDecimal ratio = bar.getClose().divide(original.getClose(), 8, java.math.RoundingMode.HALF_UP);
            if (previous != null && ratio.subtract(previous).abs().compareTo(new java.math.BigDecimal("0.001")) > 0)
                actions.add(new CorporateAction(bar.getDate(), ratio.divide(previous, 8, java.math.RoundingMode.HALF_UP), "ADJUSTMENT_RATIO_CHANGE;TYPE_UNSPECIFIED"));
            previous = ratio;
        }
        return actions;
    }

    /** 最近资讯失败只影响该分组；不将接口失败描述为没有新闻或公告。 */
    private DataFeed<MarketArticle> articles(String symbol, LocalDate cutoff, boolean announcement) {
        String source = announcement ? ANNOUNCEMENTS : NEWS;
        try {
            if (Thread.currentThread().isInterrupted()) { throw new java.io.InterruptedIOException(); }
            String url = announcement ? source + "?page_size=20&page_index=1&ann_type=A&stock_list=" + symbol
                    : source + "?cb=p9callback&param=" + encode(newsQuery(symbol));
            String raw = client.get(url);
            // 只接收精确的固定回调包装，不执行 JavaScript，也不宽松截取任意括号内容。
            String payload = raw.trim();
            if (!announcement && payload.startsWith("p9callback(") && (payload.endsWith(")") || payload.endsWith(");"))) {
                payload = payload.substring("p9callback(".length(), payload.lastIndexOf(')'));
            }
            JsonNode root = json.readTree(payload);
            if ((announcement && root.path("success").asInt() != 1)
                    || (!announcement && root.path("code").asInt(-1) != 0)) { throw new IllegalArgumentException("资讯状态异常"); }
            JsonNode rows = announcement ? root.path("data").path("list") : root.path("result").path("cmsArticleWebOld");
            if (!rows.isArray()) { throw new IllegalArgumentException("资讯结构异常"); }
            List<MarketArticle> items = new ArrayList<MarketArticle>(); Set<String> ids = new HashSet<String>();
            for (JsonNode row : rows) {
                if (announcement && !belongsTo(row.path("codes"), symbol)) { continue; }
                String dateText = required(row, announcement ? "notice_date" : "date");
                LocalDate date = LocalDate.parse(dateText.substring(0,10));
                if (date.isAfter(cutoff) || date.isBefore(cutoff.minusDays(30))) { continue; }
                String id = required(row, announcement ? "art_code" : "code");
                if (!ids.add(id)) { continue; }
                String link = announcement ? "https://data.eastmoney.com/notices/detail/" + symbol + "/" + encode(id) + ".html" : required(row,"url");
                java.net.URI uri = java.net.URI.create(link);
                if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                    throw new IllegalArgumentException("原文链接无效");
                }
                String publishedAt = announcement ? date.toString() :
                        LocalDateTime.parse(dateText, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "+08:00";
                MarketArticle item = new MarketArticle(id, clean(required(row,"title"),300),
                        clean(row.path("content").asText(""),1500), publishedAt, link,
                        announcement ? "东方财富公告列表" : clean(row.path("mediaName").asText("未知媒体"),100));
                if (announcement) {
                    AnnouncementTextProvider.FullText full = announcementTextProvider.fetch(item);
                    item = new MarketArticle(id, item.getTitle(), item.getSummary(), item.getPublishedAt(), item.getUrl(), item.getPublisher(), full.getText(), full.getStatus());
                }
                items.add(item);
            }
            return feed(source, raw, true,
                    announcement ? "RECENT_20_ONLY;NOTICE_DATE_DAY_PRECISION;NO_FULL_TEXT" : "KEYWORD_SEARCH_RECENT_10_ONLY;NOT_COMPLETE_HISTORY",
                    items);
        } catch (Exception exception) {
            return new DataFeed<MarketArticle>(source, "", Instant.now(clock).toString(), DataStatus.UNAVAILABLE, true,
                    Thread.currentThread().isInterrupted() ? "CANCELLED" : "UPSTREAM_OR_SCHEMA_UNAVAILABLE", Collections.<MarketArticle>emptyList());
        }
    }
    /** 构建关键词查询，供应商返回的是最近窗口，不承诺历史分页完整性。 */
    private String newsQuery(String symbol) throws Exception {
        Map<String,Object> body = new LinkedHashMap<String,Object>();
        body.put("uid", ""); body.put("keyword",symbol); body.put("type",Collections.singletonList("cmsArticleWebOld"));
        body.put("client","web"); body.put("clientType","web"); body.put("clientVersion","curr");
        Map<String,Object> parameters = new LinkedHashMap<String,Object>();
        parameters.put("searchScope","default"); parameters.put("sort","default");
        parameters.put("pageIndex",1); parameters.put("pageSize",10); parameters.put("preTag",""); parameters.put("postTag","");
        body.put("param",Collections.singletonMap("cmsArticleWebOld",parameters));
        return json.writeValueAsString(body);
    }
    /** 公告必须显式包含当前证券，不能误用其他公司的公告。 */
    private boolean belongsTo(JsonNode codes, String symbol) {
        if (!codes.isArray()) { return false; }
        for (JsonNode code : codes) { if (symbol.equals(code.path("stock_code").asText())) { return true; } }
        return false;
    }
    /** 成功数据附带响应指纹和抓取时间；无条目仍是成功的 EMPTY。 */
    private <T> DataFeed<T> feed(String source, String raw, boolean limited, String detail, List<T> items) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder version = new StringBuilder("sha256:");
        for (byte value : digest) { version.append(String.format(Locale.ROOT,"%02x", value & 255)); }
        return new DataFeed<T>(source,version.toString(),Instant.now(clock).toString(),
                items.isEmpty() ? DataStatus.EMPTY : DataStatus.AVAILABLE, limited, detail, items);
    }
    /** 关键字段缺失即判为不可靠响应，不让空标题或无日期记录进入研究。 */
    private String required(JsonNode row, String key) {
        String value = row.path(key).asText("").trim();
        if (value.isEmpty()) { throw new IllegalArgumentException("缺少资讯字段"); }
        return value;
    }
    /** 清理供应商高亮标签，并限制进入模型的文本长度。 */
    private String clean(String value, int limit) {
        String text = value.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n]+", " ");
        return text.length() > limit ? text.substring(0,limit) : text;
    }
    /** 使用 UTF-8 编码固定接口查询参数。 */
    private static String encode(String value) throws java.io.UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
