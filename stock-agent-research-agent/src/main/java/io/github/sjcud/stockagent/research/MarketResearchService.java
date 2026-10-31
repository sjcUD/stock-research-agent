package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.StockDataProvider;
import io.github.sjcud.stockagent.data.StockResearchData;

/** 应用编排：由服务端数据策略取数，再进入既有多角色协作流程。 */
public class MarketResearchService {
    /** SAMPLE 或 LIVE 数据策略。 */
    private final StockDataProvider provider;
    /** 可复用的研究协作服务。 */
    private final MultiResearchService research;
    /** 注入数据策略，领域层不读取环境变量和供应商 URL。 */
    public MarketResearchService(StockDataProvider provider, MultiResearchService research) {
        if (provider == null || research == null) { throw new IllegalArgumentException("研究依赖不能为空"); }
        this.provider = provider; this.research = research;
    }
    /** 先校验请求再取数，避免无效问题或模式产生网络请求。 */
    public MarketResearchAnswer ask(String question, String symbol, String asOf, ResearchMode mode) throws Exception {
        if (question == null || question.trim().isEmpty() || mode == null) { throw new IllegalArgumentException("问题和模式不能为空"); }
        StockResearchData data = provider.load(symbol,asOf);
        return new MarketResearchAnswer(data,research.ask(question,data,mode));
    }
}
