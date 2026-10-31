package io.github.sjcud.stockagent.runtime.research;

/** 研究运行记录持久化边界。 */
public interface ResearchRunRepository {

    /** 创建一条运行记录并返回数据库生成的 ID。 */
    ResearchRunRecord create(long conversationId, String status, String requestJson);
}
