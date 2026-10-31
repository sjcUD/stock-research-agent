package io.github.sjcud.stockagent.rag.store;

/**
 * 检查当前 Embedding profile 是否与 Qdrant collection 的向量空间兼容。
 *
 * <p>向量维度和模型指纹不一致时必须在写入前失败，不能让不同空间的
 * 向量混入同一个 collection。</p>
 */
public class QdrantProfileValidator {
    private final int collectionDimensions;
    private final String collectionFingerprint;

    public QdrantProfileValidator(int collectionDimensions, String collectionFingerprint) {
        if (collectionDimensions <= 0) {
            throw new IllegalArgumentException("collectionDimensions 必须大于0");
        }
        this.collectionDimensions = collectionDimensions;
        this.collectionFingerprint = collectionFingerprint;
    }

    /** 校验即将写入的 profile。 */
    public void validate(int dimensions, String fingerprint) {
        if (dimensions != collectionDimensions) {
            throw new IllegalArgumentException("Embedding 维度不匹配");
        }
        if (!collectionFingerprint.equals(fingerprint)) {
            throw new IllegalArgumentException("Embedding profile 不匹配");
        }
    }
}
