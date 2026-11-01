package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.BudgetLedger;
import java.util.*;

/** 原Java执行器策略，保留独立角色串行/并行行为作为Graph学习对照。 */
public final class ExecutorResearchWorkflow implements ResearchWorkflow {
    /** 原分析师执行器与经理角色，均无跨请求状态。 */
    private final MultiAnalystOrchestrator orchestrator;
    private final PortfolioManager manager;
    private final int analystCount;
    /** 复用现有策略，不复制模型提示词。 */
    public ExecutorResearchWorkflow(List<ResearchAnalyst> analysts, PortfolioManager manager) {
        orchestrator = new MultiAnalystOrchestrator(analysts); analystCount = analysts.size(); this.manager = Objects.requireNonNull(manager);
    }
    /** 两阶段共用父预算，分析失败不会进入经理。 */
    @Override public WorkflowResult execute(String question, MarketSnapshot snapshot, String evidence, ResearchMode mode,
            BudgetLedger budget, ResearchProgressListener progress) throws Exception {
        List<AnalystResult> reports = orchestrator.run(question, snapshot, evidence, mode, WorkflowSupport.analysts(budget, analystCount), progress);
        PortfolioDecision decision = WorkflowSupport.manage(question, reports, manager, budget, progress, () -> Thread.currentThread().isInterrupted());
        return new WorkflowResult(decision, Collections.<String, Object>singletonMap("budget", budget.snapshot()));
    }
}
