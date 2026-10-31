package io.github.sjcud.stockagent.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 同一来源的数据分组，将内容与来源、抓取时间、版本及覆盖范围一起传递。 */
public final class DataFeed<T> {
    /** 来源名称或固定 URL；不保存密钥。 */
    private final String source;
    /** 本次响应 SHA-256，标识抓取版本，不等于供应商发布版本。 */
    private final String version;
    /** 抓取时间，UTC ISO-8601。 */
    private final String fetchedAt;
    /** 成功、有无数据或不可用状态。 */
    private final DataStatus status;
    /** 是否仅覆盖最近窗口，不能作为完整历史回放。 */
    private final boolean limitedHistory;
    /** 数据口径或失败类型说明。 */
    private final String detail;
    /** 不可变条目列表。 */
    private final List<T> items;
    /** 复制记录列表，避免多分析师修改共享事实。 */
    public DataFeed(String source, String version, String fetchedAt, DataStatus status,
                    boolean limitedHistory, String detail, List<T> items) {
        this.source = source; this.version = version; this.fetchedAt = fetchedAt;
        this.status = status; this.limitedHistory = limitedHistory; this.detail = detail;
        this.items = Collections.unmodifiableList(new ArrayList<T>(items));
    }
    /** 返回来源。 */
    public String getSource() { return source; }
    /** 返回响应版本指纹。 */
    public String getVersion() { return version; }
    /** 返回抓取时间。 */
    public String getFetchedAt() { return fetchedAt; }
    /** 返回状态。 */
    public DataStatus getStatus() { return status; }
    /** 返回历史覆盖限制。 */
    public boolean isLimitedHistory() { return limitedHistory; }
    /** 返回口径说明。 */
    public String getDetail() { return detail; }
    /** 返回不可变记录。 */
    public List<T> getItems() { return items; }
}
