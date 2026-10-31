package io.github.sjcud.stockagent.data;

/** 新闻或公告摘要；发布时间保留原始精度，正文不是完整公告 PDF。 */
public final class MarketArticle {
    /** 上游稳定 ID。 */
    private final String id;
    /** 标题。 */
    private final String title;
    /** 上游摘要，公告列表没有摘要时为空。 */
    private final String summary;
    /** 新闻为上海时间，公告仅保留 notice_date 日期，不能冒充精确披露时刻。 */
    private final String publishedAt;
    /** 原文链接，SAMPLE 示例为空。 */
    private final String url;
    /** 原发布媒体或公告平台。 */
    private final String publisher;
    /** 创建不可变资讯条目。 */
    public MarketArticle(String id, String title, String summary, String publishedAt, String url, String publisher) {
        this.id = id; this.title = title; this.summary = summary;
        this.publishedAt = publishedAt; this.url = url; this.publisher = publisher;
    }
    /** 返回 ID。 */
    public String getId() { return id; }
    /** 返回标题。 */
    public String getTitle() { return title; }
    /** 返回摘要。 */
    public String getSummary() { return summary; }
    /** 返回上海时区发布时间或仅日期。 */
    public String getPublishedAt() { return publishedAt; }
    /** 返回原文链接。 */
    public String getUrl() { return url; }
    /** 返回原发布方。 */
    public String getPublisher() { return publisher; }
}
