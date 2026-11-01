package io.github.sjcud.stockagent.data;

import java.util.regex.Pattern;

/** 东方财富公告原文适配器；仅下载已通过链接校验的 HTML，并转成纯文本。 */
public final class EastmoneyAnnouncementTextProvider implements AnnouncementTextProvider {
    private final HttpTextClient client; private final int maxChars;
    public EastmoneyAnnouncementTextProvider(HttpTextClient client, int maxChars) { if (client == null || maxChars < 1) throw new IllegalArgumentException("全文抓取配置无效"); this.client = client; this.maxChars = maxChars; }
    @Override public FullText fetch(MarketArticle article) {
        try {
            if (article == null || article.getUrl() == null || article.getUrl().trim().isEmpty()) return new FullText("", DataStatus.UNAVAILABLE);
            String raw = client.get(article.getUrl());
            String text = Pattern.compile("<[^>]+>").matcher(raw).replaceAll(" ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
            if (text.length() > maxChars) text = text.substring(0, maxChars) + "…[全文截断]";
            return new FullText(text, text.isEmpty() ? DataStatus.EMPTY : DataStatus.AVAILABLE);
        } catch (Exception ex) { return new FullText("", DataStatus.UNAVAILABLE); }
    }
}
