package io.github.sjcud.stockagent.llm;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Spring AI EmbeddingModel 适配器。
 *
 * <p>供应商模型由 Spring 配置创建，业务层只依赖 EmbeddingGateway；因此
 * 可以在不改 RAG 编排的情况下切换 OpenAI、DeepSeek 或本地模型。</p>
 */
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private final EmbeddingModel delegate;

    public SpringAiEmbeddingGateway(EmbeddingModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("EmbeddingModel 不能为空");
        }
        this.delegate = delegate;
    }

    /** 保持输入顺序调用 Spring AI 批量向量接口。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("待向量化文本不能为空");
        }
        return delegate.embed(texts);
    }
}
