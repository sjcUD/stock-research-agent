package io.github.sjcud.stockagent.rag.retrieval;

/** RAG 检索请求，业务层只表达查询和数量，不暴露 Qdrant DSL。 */
public final class RetrievalQuery {
    private final String text;
    private final int topK;

    public RetrievalQuery(String text, int topK) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("检索文本不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于0");
        }
        this.text = text;
        this.topK = topK;
    }

    public String getText() { return text; }
    public int getTopK() { return topK; }
}
