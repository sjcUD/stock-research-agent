package io.github.sjcud.stockagent.data;

import java.math.BigDecimal;

/** 外部情绪聚合快照；分数范围固定为[-1,1]，不把新闻数量冒充情绪判断。 */
public final class SentimentSnapshot {
    private final String asOf; private final BigDecimal score; private final int sampleCount;
    public SentimentSnapshot(String asOf, String score, int sampleCount) {
        java.time.LocalDate.parse(asOf); this.score=new BigDecimal(score);
        if (this.score.compareTo(new BigDecimal("-1"))<0 || this.score.compareTo(BigDecimal.ONE)>0 || sampleCount<0) throw new IllegalArgumentException("情绪快照无效");
        this.asOf=asOf; this.sampleCount=sampleCount;
    }
    public String getAsOf(){return asOf;} public BigDecimal getScore(){return score;} public int getSampleCount(){return sampleCount;}
}
