package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.BudgetLedger;
import io.github.sjcud.stockagent.runtime.harness.FailureClassifier;
import io.github.sjcud.stockagent.runtime.harness.HarnessCallExecutor;
import java.util.*;
import java.util.concurrent.*;

/** 扇出/汇聚编排：独立分析同一证据，共用父预算，按稳定角色名汇聚。 */
public class MultiAnalystOrchestrator {
    /** 不可变的角色注册列表。 */
    private final List<ResearchAnalyst> analysts;
    /** 所有角色共用的预算和错误处理入口。 */
    private final HarnessCallExecutor harness = new HarnessCallExecutor(new FailureClassifier());

    /** 验证角色唯一，避免覆盖或重复计算同一职责。 */
    public MultiAnalystOrchestrator(List<ResearchAnalyst> analysts) {
        if (analysts == null || analysts.isEmpty()) {
            throw new IllegalArgumentException("analysts 不能为空");
        }
        Set<AnalystRole> roles = EnumSet.noneOf(AnalystRole.class);
        for (ResearchAnalyst analyst : analysts) {
            if (analyst == null || analyst.getRole() == null || !roles.add(analyst.getRole())) {
                throw new IllegalArgumentException("分析师角色必须非空且唯一");
            }
        }
        this.analysts = Collections.unmodifiableList(new ArrayList<ResearchAnalyst>(analysts));
    }
    /** 串行独立分析示例，不把前一个角色报告传给下一个角色。 */
    public List<AnalystResult> runSerial(String question, MarketSnapshot snapshot, String evidence) {
        try {
            return run(question, snapshot, evidence, ResearchMode.SERIAL, defaultBudget());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("研究被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("串行研究失败", exception);
        }
    }
    /** 并行独立分析示例。 */
    public List<AnalystResult> runParallel(String question, MarketSnapshot snapshot, String evidence)
            throws Exception {
        return run(question, snapshot, evidence, ResearchMode.PARALLEL, defaultBudget());
    }
    /** 调用方可将检索、分析师和组合经理绑定到同一预算账本。 */
    public List<AnalystResult> run(String question, MarketSnapshot snapshot, String evidence,
                                  ResearchMode mode, BudgetLedger ledger) throws Exception {
        return run(question,snapshot,evidence,mode,ledger,ResearchProgressListener.NONE);
    }
    /** 附带真实角色进度的编排；观察者必须可并发调用。 */
    public List<AnalystResult> run(String question, MarketSnapshot snapshot, String evidence,
            ResearchMode mode, BudgetLedger ledger, ResearchProgressListener progress) throws Exception {
        if (question == null || question.trim().isEmpty() || snapshot == null || mode == null || ledger == null) {
            throw new IllegalArgumentException("研究输入不能为空");
        }
        // 串行模式仍放入单线程执行器，保证阻塞模型不能无限阻塞调用线程。
        ExecutorService pool = Executors.newFixedThreadPool(mode == ResearchMode.SERIAL ? 1 : Math.min(8, analysts.size()));
        List<Future<AnalystResult>> futures = new ArrayList<Future<AnalystResult>>();
        CompletionService<AnalystResult> completed = new ExecutorCompletionService<AnalystResult>(pool);
        try {
            List<AnalystResult> results = new ArrayList<AnalystResult>();
            for (final ResearchAnalyst analyst : analysts) {
                futures.add(completed.submit(() -> harness.execute(() -> {
                    progress.onProgress("ANALYST",analyst.getRole().name(),"STARTED");
                    AnalystResult result = analyst.analyze(question, snapshot, evidence);
                    if (result == null || result.getRole() != analyst.getRole()) {
                        throw new IllegalStateException("分析师返回角色与注册角色不一致");
                    }
                    progress.onProgress("ANALYST",analyst.getRole().name(),"COMPLETED");
                    return result;
                }, ledger, 1, 0L, () -> Thread.currentThread().isInterrupted(),
                        analyst.estimateTokens(question, snapshot, evidence))));
                // 串行必须先消费当前结果再提交下一个，失败时不会启动后续模型。
                if (mode == ResearchMode.SERIAL) { results.add(await(completed, ledger)); }
            }
            if (mode == ResearchMode.PARALLEL) {
                for (int index = 0; index < analysts.size(); index++) {
                    results.add(await(completed, ledger));
                }
            }
            Collections.sort(results, Comparator.comparing(result -> result.getRole().name()));
            return Collections.unmodifiableList(results);
        } finally {
            // 失败、超时、中断时均发送取消信号；底层 HTTP 仍需配置自身读取超时。
            for (Future<AnalystResult> future : futures) { future.cancel(true); }
            pool.shutdownNow();
        }
    }
    /** 按完成顺序消费结果，后注册角色失败时也能立即取消其他任务。 */
    private AnalystResult await(CompletionService<AnalystResult> completed, BudgetLedger ledger) throws Exception {
        Future<AnalystResult> future = completed.poll(ledger.remainingMillis(), TimeUnit.MILLISECONDS);
        if (future == null) { throw new TimeoutException("分析师执行超过研究截止时间"); }
        return future.get();
    }
    /** 保守估算字符输入加输出预留；不是模型账单中的真实 usage。 */
    static int estimateTokens(String question, String evidence) {
        long estimate = (long) question.length() + (evidence == null ? 0 : evidence.length()) + 2500L;
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }
    /** 独立调用示例的默认预算。 */
    private BudgetLedger defaultBudget() {
        return new BudgetLedger(analysts.size(), System.currentTimeMillis() + 120000L, 60000);
    }
}
