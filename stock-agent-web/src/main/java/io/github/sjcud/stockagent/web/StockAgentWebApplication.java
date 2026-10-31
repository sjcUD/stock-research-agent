package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.runtime.research.InMemoryResearchRunRepository;
import io.github.sjcud.stockagent.runtime.research.ResearchRunRepository;
import io.github.sjcud.stockagent.runtime.research.ResearchRunService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** 股票研究 Agent Web 入口；默认使用内存仓储，便于本地学习和冒烟。 */
@SpringBootApplication
public class StockAgentWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockAgentWebApplication.class, args);
    }

    /** 默认仓储；部署 MySQL 时替换为 JdbcResearchRunRepository Bean。 */
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
