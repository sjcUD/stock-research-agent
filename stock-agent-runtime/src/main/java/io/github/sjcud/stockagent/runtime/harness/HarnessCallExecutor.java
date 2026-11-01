package io.github.sjcud.stockagent.runtime.harness;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * 受预算、截止时间、取消和有限重试约束的调用执行器。
 *
 * <p>每次重试都会重新预留模型额度；不可重试错误、预算耗尽、超时或取消 会立即结束，不把失败隐藏成无限等待。
 */
public class HarnessCallExecutor {

    private final FailureClassifier failureClassifier;

    public HarnessCallExecutor(FailureClassifier failureClassifier) {
        if (failureClassifier == null) {
            throw new IllegalArgumentException("failureClassifier 不能为空");
        }
        this.failureClassifier = failureClassifier;
    }

    /** 执行一次有界调用。 */
    public <T> T execute(
            Callable<T> action,
            BudgetLedger ledger,
            int maxAttempts,
            long backoffMillis,
            BooleanSupplier cancelled)
            throws Exception {
        return execute(action, ledger, maxAttempts, backoffMillis, cancelled, 0);
    }

    /** 每次尝试均先检查状态，再一次性预留调用与 Token 预算。失败尝试也可能产生费用，不退款。 */
    public <T> T execute(
            Callable<T> action,
            BudgetLedger ledger,
            int maxAttempts,
            long backoffMillis,
            BooleanSupplier cancelled,
            int estimatedTokens)
            throws Exception {
        if (action == null || ledger == null || cancelled == null) {
            throw new IllegalArgumentException("执行依赖不能为空");
        }
        if (maxAttempts <= 0 || backoffMillis < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("重试参数不合法");
        }
        RetryableOperationException lastRetryable = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            checkState(ledger, cancelled);
            BudgetLedger.Reservation reservation = ledger.reserveAttemptWithReceipt(estimatedTokens);
            if (reservation == null) {
                throw new BudgetExceededException();
            }
            try {
                T result = new DeadlineExecutor().call(() -> {
                    try (ModelUsageScope scope = ModelUsageScope.bind(reservation, ledger, cancelled)) {return action.call();}
                }, ledger, cancelled);
                checkState(ledger, cancelled); return result;
            } catch (RetryableOperationException exception) {
                lastRetryable = exception;
                if (failureClassifier.classify(exception.getStatusCode()) != FailureClass.RETRYABLE
                        || attempt == maxAttempts) {
                    throw exception;
                }
                sleepWithinDeadline(backoffMillis, ledger, cancelled);
            } finally { reservation.markUnknown(); }
        }
        throw lastRetryable == null ? new IllegalStateException("调用未执行") : lastRetryable;
    }

    private void checkState(BudgetLedger ledger, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new IllegalStateException("研究任务已取消");
        }
        if (ledger.isOverdrawn()) throw new BudgetExceededException();
        if (ledger.isExpired()) {
            throw new HarnessTimeoutException();
        }
    }

    private void sleepWithinDeadline(
            long backoffMillis, BudgetLedger ledger, BooleanSupplier cancelled)
            throws InterruptedException {
        long sleepMillis = Math.min(backoffMillis, ledger.remainingMillis());
        if (sleepMillis > 0) {
            Thread.sleep(sleepMillis);
        }
        checkState(ledger, cancelled);
    }
}
