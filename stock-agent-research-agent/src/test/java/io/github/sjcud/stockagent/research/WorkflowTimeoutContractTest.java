package io.github.sjcud.stockagent.research;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** 引擎切换不能改变经理阶段超时的HTTP可分类契约。 */
public class WorkflowTimeoutContractTest {
    @Test public void bothEnginesMustExposeManagerTimeoutConsistently() {
        List<ResearchAnalyst> roles = Arrays.asList(new PromptResearchAnalyst(AnalystRole.RISK, "风险", p -> "报告"));
        PortfolioManager manager = new PortfolioManager(p -> { throw new HarnessTimeoutException(); });
        for (ResearchWorkflow workflow : Arrays.asList(new ExecutorResearchWorkflow(roles, manager), new GraphResearchWorkflow(roles, manager))) {
            assertThrows(TimeoutException.class, () -> workflow.execute("q", new MarketSnapshot("600519", "1", "2026-10-30", "SAMPLE"), "e",
                    ResearchMode.SERIAL, new BudgetLedger(2, System.currentTimeMillis()+3000, 60000), ResearchProgressListener.NONE));
        }
    }
}
