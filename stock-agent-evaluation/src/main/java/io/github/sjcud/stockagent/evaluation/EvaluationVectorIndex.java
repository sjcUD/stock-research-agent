package io.github.sjcud.stockagent.evaluation;

import io.github.sjcud.stockagent.llm.EmbeddingGateway;
import io.github.sjcud.stockagent.rag.chunking.DocumentChunk;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;

import java.util.*;

/** 仅供评测的内存余弦索引：隔离每个模型空间，不作为生产 SimpleVectorStore 实现。 */
public final class EvaluationVectorIndex implements VectorStore {
    /** 每个索引绑定一个网关和固定维度；模型改变必须重建。 */
    private final EmbeddingGateway gateway;

    private final int dimensions;

    /** 插入顺序稳定，相同相似度以片段 ID 排序，保证离线可复现。 */
    private final Map<String, DocumentChunk> chunks = new LinkedHashMap<String, DocumentChunk>();

    private final Map<String, float[]> vectors = new HashMap<String, float[]>();

    /** 绑定本次评测所需的独立依赖与模型配置。 */
    public EvaluationVectorIndex(EmbeddingGateway gateway, int dimensions) {
        if (gateway == null || dimensions < 1) throw new IllegalArgumentException("无效模型空间");
        this.gateway = gateway;
        this.dimensions = dimensions;
    }

    /** 完整验证本批向量后才更新，避免部分错误造成新旧状态混杂。 */
    @Override
    public void upsert(List<DocumentChunk> input) {
        List<String> texts = new ArrayList<String>();
        for (DocumentChunk c : input) texts.add(c.getText());
        List<float[]> embedded = embed(texts);
        for (int i = 0; i < input.size(); i++) {
            chunks.put(input.get(i).getChunkId(), input.get(i));
            vectors.put(input.get(i).getChunkId(), embedded.get(i));
        }
    }

    /** 查询只取查询文本；标准答案不进入 Embedding 输入。 */
    @Override
    public List<RetrievalHit> search(RetrievalQuery query) {
        float[] vector = embed(Collections.singletonList(query.getText())).get(0);
        List<RetrievalHit> hits = new ArrayList<RetrievalHit>();
        for (DocumentChunk c : chunks.values())
            hits.add(
                    new RetrievalHit(
                            c.getChunkId(),
                            c.getText(),
                            cosine(vector, vectors.get(c.getChunkId())),
                            new Citation(
                                    c.getDocumentId(),
                                    c.getDocumentRevision(),
                                    c.getPage(),
                                    c.getOffset())));
        hits.sort(
                Comparator.comparingDouble(RetrievalHit::getScore)
                        .reversed()
                        .thenComparing(RetrievalHit::getChunkId));
        return new ArrayList<RetrievalHit>(hits.subList(0, Math.min(query.getTopK(), hits.size())));
    }

    /** 检查实际返回长度、有限数及非零范数，禁止维度不匹配时静默截断。 */
    private List<float[]> embed(List<String> texts) {
        List<float[]> result = gateway.embed(texts);
        if (result == null || result.size() != texts.size())
            throw new IllegalStateException("向量数量错误");
        for (float[] v : result) {
            if (v == null || v.length != dimensions)
                throw new IllegalStateException("实际向量维度与评测配置不一致");
            double norm = 0;
            for (float x : v) {
                if (!Float.isFinite(x)) throw new IllegalStateException("非有限向量");
                norm += (double) x * x;
            }
            if (norm == 0) throw new IllegalStateException("零向量");
        }
        return result;
    }

    /** 余弦以 double 累加，减小长向量浮点误差。 */
    private double cosine(float[] a, float[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        return dot / Math.sqrt(aa * bb);
    }

    /** 字符二元组哈希基线，没有语义模型能力；只验证指标与配置流水线。 */
    public static EmbeddingGateway hashBaseline(final int dimensions) {
        if (dimensions < 1) throw new IllegalArgumentException("dimensions 必须为正");
        return texts -> {
            List<float[]> result = new ArrayList<float[]>();
            for (String text : texts) {
                float[] v = new float[dimensions];
                for (int i = 0; i < text.length(); i++) {
                    String gram = text.substring(i, Math.min(i + 2, text.length()));
                    v[Math.floorMod(gram.hashCode(), dimensions)]++;
                }
                result.add(v);
            }
            return result;
        };
    }
}
