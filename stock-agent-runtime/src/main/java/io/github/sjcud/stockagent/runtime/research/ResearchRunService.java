package io.github.sjcud.stockagent.runtime.research;

/** 研究运行记录应用服务，隔离 API 与具体仓储实现。 */
public class ResearchRunService {

    private final ResearchRunRepository repository;

    public ResearchRunService(ResearchRunRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("ResearchRunRepository 不能为空");
        }
        this.repository = repository;
    }

    /** 创建排队状态的研究运行记录。 */
    public ResearchRunRecord start(long conversationId, String requestJson) {
        return repository.create(conversationId, "QUEUED", requestJson);
    }
}
