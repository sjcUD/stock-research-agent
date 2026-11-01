package io.github.sjcud.stockagent.data;

/** 公告全文策略边界；列表与全文分离，避免把标题摘要误认为披露原文。 */
public interface AnnouncementTextProvider {
    /** 获取单条公告全文，失败时返回明确状态而不是抛出并丢失整组列表。 */
    FullText fetch(MarketArticle article);
    /** 全文结果和值状态。 */
    final class FullText {
        private final String text; private final DataStatus status;
        public FullText(String text, DataStatus status) { this.text = text == null ? "" : text; this.status = status == null ? DataStatus.UNAVAILABLE : status; }
        public String getText() { return text; } public DataStatus getStatus() { return status; }
    }
}
