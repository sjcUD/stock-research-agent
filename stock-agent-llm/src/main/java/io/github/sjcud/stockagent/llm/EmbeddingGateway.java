package io.github.sjcud.stockagent.llm;

import java.util.List;

/** Embedding 能力的统一接口，隐藏批处理和供应商请求格式。 */
public interface EmbeddingGateway {

    /**
     * 批量生成向量；实现必须保证返回顺序与输入顺序一致。
     */
    List<float[]> embed(List<String> texts);
}
