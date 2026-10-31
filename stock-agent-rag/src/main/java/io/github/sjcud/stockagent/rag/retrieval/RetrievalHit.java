package io.github.sjcud.stockagent.rag.retrieval;

/** 检索命中，保留后续引用抽屉需要的 revision/page/chunk 信息。 */
public final class RetrievalHit {
    private final String chunkId;
    private final String text;
    private final double score;

    public RetrievalHit(String chunkId, String text, double score) {
        this.chunkId = chunkId;
        this.text = text;
        this.score = score;
    }

    public String getChunkId() { return chunkId; }
    public String getText() { return text; }
    public double getScore() { return score; }
}
