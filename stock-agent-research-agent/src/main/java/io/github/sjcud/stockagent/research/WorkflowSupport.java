package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.runtime.harness.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 两种引擎共用阶段额度和经理调用，避免切换框架改变Harness规则。 */
final class WorkflowSupport {
    private WorkflowSupport() { }
    /** 复制并验证角色注册表，拒绝重复职责。 */
    static List<ResearchAnalyst> validate(List<ResearchAnalyst> analysts) {
        new MultiAnalystOrchestrator(analysts);
        return Collections.unmodifiableList(new ArrayList<ResearchAnalyst>(analysts));
    }
    /** 创建分析阶段预算，父级不足时仍在调用预留时原子拒绝。 */
    static BudgetLedger analysts(BudgetLedger root, int count) {
        return root.child("ANALYSTS", count, 45000, Math.max(1L, Math.min(110000L, root.remainingMillis())));
    }
    /** 进入经理阶段才启动其30秒时限，不能提前消耗在等待分析师上。 */
    static PortfolioDecision manage(String question, List<AnalystResult> reports, PortfolioManager manager,
            BudgetLedger root, ResearchProgressListener progress, BooleanSupplier cancelled) throws Exception {
        BudgetLedger budget = root.child("MANAGER", 1, 15000, Math.max(1L, Math.min(30000L, root.remainingMillis())));
        try {
            return new HarnessCallExecutor(new FailureClassifier()).execute(() -> {
            progress.onProgress("MANAGER", "PORTFOLIO_MANAGER", "STARTED");
            PortfolioDecision decision = manager.decide(question, reports);
            if (cancelled.getAsBoolean()) throw new IllegalStateException("研究已取消");
            if (decision.getTrace() != null) progress.onTrace(decision.getTrace());
            progress.onProgress("MANAGER", "PORTFOLIO_MANAGER", "COMPLETED"); return decision;
            }, budget, 1, 0L, cancelled, manager.estimateTokens(question, reports));
        } catch (HarnessTimeoutException failure) {
            // 两种引擎通过同一种受检异常让Web层稳定映射为504。
            java.util.concurrent.TimeoutException timeout = new java.util.concurrent.TimeoutException("经理阶段达到截止时间");
            timeout.initCause(failure); throw timeout;
        }
    }
}
