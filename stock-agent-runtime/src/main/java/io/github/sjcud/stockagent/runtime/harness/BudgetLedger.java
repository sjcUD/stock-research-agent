package io.github.sjcud.stockagent.runtime.harness;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个研究 run 共用的最小预算账本。
 *
 * <p>reserveModel 使用原子递减，保证并行角色竞争最后一次模型额度时只有
 * 一个成功。后续会扩展工具、Embedding、摘要和实际 usage 结算。</p>
 */
public class BudgetLedger {
    private final AtomicInteger remainingModelCalls;
    private final long deadlineMillis;

    public BudgetLedger(int modelCalls, long deadlineMillis) {
        if (modelCalls < 0 || deadlineMillis <= 0) {
            throw new IllegalArgumentException("预算必须为非负且 deadline 必须大于0");
        }
        this.remainingModelCalls = new AtomicInteger(modelCalls);
        this.deadlineMillis = deadlineMillis;
    }

    /** 原子预留一次模型额度。 */
    public boolean reserveModel() {
        while (true) {
            int current = remainingModelCalls.get();
            if (current <= 0) {
                return false;
            }
            if (remainingModelCalls.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    /** 返回配置的活跃时间上限，后续由 DeadlineController 结合单调时钟计算。 */
    public long getDeadlineMillis() {
        return deadlineMillis;
    }
}
