package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.BudgetLedger;

/** 研究编排策略边界；取数/检索/压缩在上层共用，角色与经理在策略内调度。 */
public interface ResearchWorkflow {
    /** 在同一父预算内执行全部角色和一次汇总；失败不能返回残缺的成功结果。 */
    WorkflowResult execute(String question, MarketSnapshot snapshot, String evidence, ResearchMode mode,
            BudgetLedger budget, ResearchProgressListener progress) throws Exception;
}
