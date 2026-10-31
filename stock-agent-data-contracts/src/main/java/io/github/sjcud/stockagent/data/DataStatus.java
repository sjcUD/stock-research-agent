package io.github.sjcud.stockagent.data;

/** 区分成功无记录与上游不可用，避免模型把采集失败当作没有事件。 */
public enum DataStatus {
    /** 成功且存在记录。 */
    AVAILABLE,
    /** 成功但日期过滤后没有记录，不代表市场中不存在相关信息。 */
    EMPTY,
    /** 接口、限流或解析失败，当前分组未获得可靠数据。 */
    UNAVAILABLE
}
