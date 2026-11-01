package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.research.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 多分析师聊天入口；原单分析师接口继续作为对照实验。 */
@RestController
@RequestMapping("/api/research/chat/multi")
public class MultiResearchController {
    /** 延迟查找服务，允许未配置 Qdrant 时启动 Web 应用。 */
    private final ObjectProvider<MultiResearchService> services;

    /** 注入按需服务提供器。 */
    public MultiResearchController(ObjectProvider<MultiResearchService> services) {
        this.services = services;
    }
    /** 返回汇总、五份角色报告、引用和完整执行轨迹；默认使用并行模式。 */
    @PostMapping
    public MultiResearchAnswer ask(@RequestBody MultiRequest request) {
        MultiResearchService service = services.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置 ChatModel 和 Qdrant");
        }
        if (request == null || blank(request.getQuestion()) || blank(request.getSymbol())
                || blank(request.getClose()) || blank(request.getAsOf()) || blank(request.getSource())
                || request.getMode() == null || request.getEngine() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题、行情快照和研究模式不能为空");
        }
        try {
            return service.ask(request.getQuestion(), new MarketSnapshot(request.getSymbol(), request.getClose(),
                    request.getAsOf(), request.getSource()), request.getMode(), request.getEngine());
        } catch (TimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "研究超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "研究已中断", exception);
        } catch (Exception exception) {
            // 不将供应商原始异常文本暴露给客户端，服务端仍保留 cause 便于排查。
            Throwable cause = exception instanceof ExecutionException ? exception.getCause() : exception;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "研究未完成，请检查模型服务或预算", cause);
        }
    }
    /** 判断请求文本是否缺失。 */
    private boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    /** 复用单分析师行情请求契约，增加调度模式。 */
    public static class MultiRequest extends ResearchChatController.ChatRequest {
        /** 默认并行；可设置 SERIAL 进行对照学习。 */
        private ResearchMode mode = ResearchMode.PARALLEL;
        /** 默认保留原Java执行器；显式ALIBABA_GRAPH启用框架图。 */
        private ResearchEngine engine = ResearchEngine.JAVA_EXECUTOR;
        /** 返回编排引擎。 */
        public ResearchEngine getEngine() { return engine; }
        /** 非法JSON枚举值由Spring返回400。 */
        public void setEngine(ResearchEngine engine) { this.engine = engine; }
        /** 返回调度模式。 */
        public ResearchMode getMode() { return mode; }
        /** 设置调度模式，JSON 非法枚举由 Spring 返回 400。 */
        public void setMode(ResearchMode mode) { this.mode = mode; }
    }
}
