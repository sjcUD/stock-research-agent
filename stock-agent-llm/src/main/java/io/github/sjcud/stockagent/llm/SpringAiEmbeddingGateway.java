package io.github.sjcud.stockagent.llm;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * Spring AI EmbeddingModel 适配器。
 *
 * <p>供应商模型由 Spring 配置创建，业务层只依赖 EmbeddingGateway；因此 可以在不改 RAG 编排的情况下切换 OpenAI、DeepSeek 或本地模型。
 */
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    /** Spring AI 协议适配对象，保持路径和响应处理由框架负责。 */
    private final EmbeddingModel delegate;

    /** 单个请求最多提交的文本数；默认10适配已验证端点，可通过构造参数扩展。 */
    private final int batchSize;

    public SpringAiEmbeddingGateway(EmbeddingModel delegate) {
        this(delegate, 10);
    }

    /** 显式控制供应商批量限制，禁止根据 URL 推断能力。 */
    public SpringAiEmbeddingGateway(EmbeddingModel delegate, int batchSize) {
        if (delegate == null || batchSize <= 0) {
            throw new IllegalArgumentException("EmbeddingModel 不能为空");
        }
        this.delegate = delegate;
        this.batchSize = batchSize;
    }

    /** 保持输入顺序调用 Spring AI 批量向量接口。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        List<float[]> result = new java.util.ArrayList<float[]>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            List<float[]> vectors = delegate.embed(batch);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("Embedding 返回数量与输入不一致");
            }
            result.addAll(vectors);
        }
        return result;
    }
}
