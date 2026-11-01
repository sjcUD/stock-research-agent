package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.StockDataProvider;
import io.github.sjcud.stockagent.data.PriceAdjustmentMode;
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
        return ask(question, symbol, asOf, mode, ResearchEngine.JAVA_EXECUTOR);
    }
    /** 相同行情提供器的引擎对照，不额外触发重复取数。 */
    public MarketResearchAnswer ask(String question, String symbol, String asOf, ResearchMode mode, ResearchEngine engine) throws Exception {
        return ask(question, symbol, asOf, mode, engine, PriceAdjustmentMode.UNADJUSTED);
    }
    /** 托管研究按服务端复权口径加载行情，不能信任客户端传入价格。 */
    public MarketResearchAnswer ask(String question, String symbol, String asOf, ResearchMode mode, ResearchEngine engine, PriceAdjustmentMode adjustment) throws Exception {
        if (engine == null || adjustment == null) throw new IllegalArgumentException("引擎和复权口径不能为空");
        if (question == null || question.trim().isEmpty() || mode == null) { throw new IllegalArgumentException("问题和模式不能为空"); }
        StockResearchData data = provider.load(symbol,asOf, adjustment);
        return new MarketResearchAnswer(data,research.ask(question,data,mode,ResearchProgressListener.NONE,"",engine));
    }
}
