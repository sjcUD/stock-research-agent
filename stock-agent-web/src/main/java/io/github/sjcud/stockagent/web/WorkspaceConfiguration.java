package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.*;
import io.github.sjcud.stockagent.research.*;
import io.github.sjcud.stockagent.runtime.workspace.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

/** 工作台应用装配：数据与模型策略保持原边界，HTTP 层只协调生命周期。 */
@Configuration
public class WorkspaceConfiguration {
    /** 创建有界任务服务，在应用关闭时释放所有自有执行器。 */
    public ResearchTaskService researchTaskService(
            StockDataProvider provider,
            ObjectProvider<MultiResearchService> multi,
            ObjectProvider<ResearchChatService> documents) {
        return build(provider, multi, documents, new InMemoryWorkspaceRepository());
    }

    /** 根据真实DataSource选择持久仓储；JDBC初始化失败直接阻止启动。 */
    @Bean(destroyMethod = "close")
    public WorkspaceRepository workspaceRepository(ObjectProvider<DataSource> source) {
        DataSource value = source.getIfAvailable();
        return value == null
                ? new InMemoryWorkspaceRepository()
                : new JdbcWorkspaceRepository(value);
    }

    /** 任务协调器依赖归档Bean，关闭顺序保证先中断任务再释放数据库所有权。 */
    @Bean(destroyMethod = "close")
    public ResearchTaskService persistentResearchTaskService(
            StockDataProvider provider,
            ObjectProvider<MultiResearchService> multi,
            ObjectProvider<ResearchChatService> documents,
            WorkspaceRepository archive) {
        return build(provider, multi, documents, archive);
    }

    /** 工作策略与保存策略组合，避免持久层直接调用模型。 */
    private ResearchTaskService build(
            StockDataProvider provider,
            ObjectProvider<MultiResearchService> multi,
            ObjectProvider<ResearchChatService> documents,
            WorkspaceRepository archive) {
        return new ResearchTaskService(
                (request, progress) -> {
                    if (request.getScope() == ResearchTaskService.Scope.DOCUMENTS) {
                        ResearchChatService service = documents.getIfAvailable();
                        if (service == null) {
                            throw new IllegalStateException("资料服务未配置");
                        }
                        // 资料问答无需调用行情接口，不用猜测的报价或行情失败阻断已上传资料。
                        MarketSnapshot context =
                                new MarketSnapshot(
                                        request.getSymbol(),
                                        "未提供市场价格",
                                        request.getAsOf(),
                                        "DOCUMENT_CONTEXT_ONLY");
                        return service.ask(
                                request.getQuestion(),
                                context,
                                () -> Thread.currentThread().isInterrupted(),
                                progress,
                                request.getHistoryContext());
                    }
                    progress.onProgress("DATA", "", "STARTED");
                    StockResearchData data = provider.load(request.getSymbol(), request.getAsOf());
                    progress.onProgress("DATA", "", "COMPLETED");
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("研究取消");
                    }
                    MultiResearchService service = multi.getIfAvailable();
                    if (service == null) {
                        throw new IllegalStateException("研究模型未配置");
                    }
                    return new MarketResearchAnswer(
                            data,
                            service.ask(request.getQuestion(), data, request.getMode(), progress,
                                    request.getHistoryContext(), request.getEngine()));
                },
                archive);
    }
}
