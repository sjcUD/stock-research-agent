package io.github.sjcud.stockagent.llm;

import java.util.List;

/** 重排能力的统一接口。 */
public interface RerankGateway {

    /**
     * 返回候选文本对应的相关性分数，顺序必须与输入候选一致。
     */
    List<Double> score(String query, List<String> candidates);
}
