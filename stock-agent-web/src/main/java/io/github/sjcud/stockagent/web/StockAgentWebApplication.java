package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.runtime.research.InMemoryResearchRunRepository;
import io.github.sjcud.stockagent.runtime.research.ResearchRunRepository;
import io.github.sjcud.stockagent.runtime.research.ResearchRunService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 股票研究Agent Web入口；工作台根据DataSource选择MySQL或显式内存模式。 */
@SpringBootApplication
public class StockAgentWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAgentWebApplication.class, args);
    }

    /** 保留最小research/runs教学接口的内存仓储；工作台历史由独立WorkspaceRepository管理。 */
    @Bean
    public ResearchRunRepository researchRunRepository() {
        return new InMemoryResearchRunRepository();
    }

    /** 创建研究运行服务。 */
    @Bean
    public ResearchRunService researchRunService(ResearchRunRepository repository) {
        return new ResearchRunService(repository);
    }
}
