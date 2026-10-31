package io.github.sjcud.stockagent.rag.retrieval;

/** 检索命中，保留后续引用抽屉需要的 revision/page/chunk 信息。 */
public final class RetrievalHit {
    private final String chunkId;
    private final String text;
    private final double score;
    private final Citation citation;

    public RetrievalHit(String chunkId, String text, double score) {
        this(chunkId, text, score, null);
    }

    public RetrievalHit(String chunkId, String text, double score, Citation citation) {
        this.chunkId = chunkId;
        this.text = text;
        this.score = score;
        this.citation = citation;
    }

    public String getChunkId() { return chunkId; }
    public String getText() { return text; }
    public double getScore() { return score; }
    public Citation getCitation() { return citation; }
}
