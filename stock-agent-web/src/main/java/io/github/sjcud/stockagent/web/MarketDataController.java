package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.research.*;
import java.time.DateTimeException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 托管取数入口：用户只传代码和日期，服务端提供行情及来源。 */
@RestController
public class MarketDataController {
    /** 由配置选择的固定数据模式，不允许用户在请求中伪造来源。 */
    private final StockDataProvider provider;
    /** 可选研究服务，纯取数接口不依赖 ChatModel 和 Qdrant。 */
    private final ObjectProvider<MultiResearchService> research;
    /** 注入数据策略和按需研究服务。 */
    public MarketDataController(StockDataProvider provider, ObjectProvider<MultiResearchService> research) {
        this.provider = provider; this.research = research;
    }
    /** 只读查询市场数据，返回实际行情日、分组状态和来源指纹。 */
    @GetMapping("/api/market/data")
    public StockResearchData data(@RequestParam("symbol") String symbol, @RequestParam("asOf") String asOf) {
        try { return provider.load(symbol,asOf); }
        catch (IllegalArgumentException | DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"证券代码或日终日期不在支持范围",exception);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"真实行情不可用，未回退样例",exception);
        }
    }
    /** 基于服务端市场事实生成五角色研究，响应保留原始数据和研究轨迹。 */
    @PostMapping("/api/research/chat/market")
    public MarketResearchAnswer research(@RequestBody MarketRequest request) {
        if (request == null || blank(request.getQuestion()) || blank(request.getSymbol())
                || blank(request.getAsOf()) || request.getMode() == null || request.getEngine() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"问题、证券代码、日期和模式不能为空");
        }
        MultiResearchService service = research.getIfAvailable();
        if (service == null) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"研究模型未就绪，请检查模型及Qdrant配置"); }
        try {
            return new MarketResearchService(provider,service).ask(request.getQuestion(),request.getSymbol(),request.getAsOf(),request.getMode(),request.getEngine());
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"研究输入无效",exception);
        } catch (TimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"研究超时",exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"研究已中断",exception);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"行情或研究服务不可用，未回退样例",exception);
        }
    }
    /** 判断字段是否为空白。 */
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    /** 托管研究请求，刻意不接受 close/source 等由服务端确定的事实。 */
    public static class MarketRequest {
        /** 用户研究问题。 */
        private String question;
        /** 沪深 A 股代码，支持明确匹配的市场前后缀。 */
        private String symbol;
        /** 上海时区已经结束的日终日期。 */
        private String asOf;
        /** 默认并行独立分析。 */
        private ResearchMode mode = ResearchMode.PARALLEL;
        /** 引擎与串行/并行方式独立，默认Java执行器。 */
        private ResearchEngine engine = ResearchEngine.JAVA_EXECUTOR;
        /** 返回引擎。 */
        public ResearchEngine getEngine() { return engine; }
        /** 设置对照引擎。 */
        public void setEngine(ResearchEngine engine) { this.engine = engine; }
        /** 返回问题。 */
        public String getQuestion() { return question; }
        /** 设置问题。 */
        public void setQuestion(String question) { this.question = question; }
        /** 返回证券代码。 */
        public String getSymbol() { return symbol; }
        /** 设置证券代码。 */
        public void setSymbol(String symbol) { this.symbol = symbol; }
        /** 返回截止日期。 */
        public String getAsOf() { return asOf; }
        /** 设置截止日期。 */
        public void setAsOf(String asOf) { this.asOf = asOf; }
        /** 返回编排模式。 */
        public ResearchMode getMode() { return mode; }
        /** 设置编排模式。 */
        public void setMode(ResearchMode mode) { this.mode = mode; }
    }
}
