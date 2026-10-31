package io.github.sjcud.stockagent.runtime.research;

/** 研究运行记录，字段与 MySQL research_run 表保持一致。 */
public final class ResearchRunRecord {

    private final Long id;
    private final long conversationId;
    private final String status;
    private final String requestJson;

    public ResearchRunRecord(Long id, long conversationId, String status, String requestJson) {
        this.id = id;
        this.conversationId = conversationId;
        this.status = requireText(status, "status");
        this.requestJson = requireText(requestJson, "requestJson");
    }

    public Long getId() { return id; }
    public long getConversationId() { return conversationId; }
    public String getStatus() { return status; }
    public String getRequestJson() { return requestJson; }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
