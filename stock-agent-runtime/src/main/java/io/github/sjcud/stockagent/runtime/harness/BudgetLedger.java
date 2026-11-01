package io.github.sjcud.stockagent.runtime.harness;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个研究 run 共用的最小预算账本。
 *
 * <p>reserveModel 使用原子递减，保证并行角色竞争最后一次模型额度时只有 一个成功。后续会扩展工具、Embedding、摘要和实际 usage 结算。
 */
public class BudgetLedger {
    private final AtomicInteger remainingModelCalls;
    private final AtomicInteger remainingTokens;
    private final long deadlineMillis;

    public BudgetLedger(int modelCalls, long deadlineMillis) {
        this(modelCalls, deadlineMillis, Integer.MAX_VALUE);
    }

    public BudgetLedger(int modelCalls, long deadlineMillis, int tokenBudget) {
        if (modelCalls < 0 || deadlineMillis <= 0 || tokenBudget < 0) {
            throw new IllegalArgumentException("预算必须为非负且 deadline 必须大于0");
        }
        this.remainingModelCalls = new AtomicInteger(modelCalls);
        this.remainingTokens = new AtomicInteger(tokenBudget);
        this.deadlineMillis = deadlineMillis;
    }

    /** 原子预留一次模型额度。 */
    public synchronized boolean reserveModel() {
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

    /** 判断当前 run 是否已经超过绝对截止时间。 */
    public boolean isExpired() {
        return System.currentTimeMillis() >= deadlineMillis;
    }

    /** 返回剩余毫秒数；已过期时返回0。 */
    public long remainingMillis() {
        return Math.max(0L, deadlineMillis - System.currentTimeMillis());
    }

    /** 原子预留估算 Token 额度。 */
    public synchronized boolean reserveTokens(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens 必须为非负");
        }
        while (true) {
            int current = remainingTokens.get();
            if (current < tokens) {
                return false;
            }
            if (remainingTokens.compareAndSet(current, current - tokens)) {
                return true;
            }
        }
    }

    /** 返回剩余 Token 预算。 */
    public int getRemainingTokens() {
        return remainingTokens.get();
    }

    /** 原子预留一次尝试的调用次数及估算 Token；任一不足均不扣减，避免并行超支。 */
    public synchronized boolean reserveAttempt(int tokens) {
        if (tokens < 0) {
            throw new IllegalArgumentException("tokens 必须为非负");
        }
        if (remainingModelCalls.get() < 1 || remainingTokens.get() < tokens) {
            return false;
        }
        remainingModelCalls.decrementAndGet();
        remainingTokens.addAndGet(-tokens);
        return true;
    }

    /** 供执行轨迹与评测检查剩余调用次数。 */
    public int getRemainingModelCalls() {
        return remainingModelCalls.get();
    }
}
