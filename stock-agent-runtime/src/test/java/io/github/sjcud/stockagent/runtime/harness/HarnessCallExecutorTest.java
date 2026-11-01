package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** 验证 Harness 的预算、重试、超时和取消边界。 */
public class HarnessCallExecutorTest {

    @Test
    public void shouldRetryRetryableFailureWithinBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BudgetLedger ledger = new BudgetLedger(2, System.currentTimeMillis() + 5000L);
        String result =
                new HarnessCallExecutor(new FailureClassifier())
                        .execute(
                                () -> {
                                    if (calls.incrementAndGet() == 1) {
                                        throw new RetryableOperationException(503, "temporary");
                                    }
                                    return "ok";
                                },
                                ledger,
                                2,
                                1L,
                                () -> false);

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    public void shouldStopWhenCancelled() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() + 5000L);

        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(() -> "never", ledger, 1, 0L, () -> true));
    }

    @Test
    public void shouldStopWhenDeadlineExpired() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() - 1L);

        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(() -> "never", ledger, 1, 0L, () -> false));
    }

    @Test
    public void shouldReserveTokenBudget() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() + 5000L, 10);

        org.junit.jupiter.api.Assertions.assertTrue(ledger.reserveTokens(6));
        org.junit.jupiter.api.Assertions.assertFalse(ledger.reserveTokens(5));
        assertEquals(4, ledger.getRemainingTokens());
    }

    /** 每一次真实尝试都要扣 Token，预算不足时不得进入第二次供应商调用。 */
    @Test
    public void shouldChargeTokensForEveryAttempt() {
        AtomicInteger calls = new AtomicInteger();
        BudgetLedger ledger = new BudgetLedger(3, System.currentTimeMillis() + 5000L, 10);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new RetryableOperationException(503, "retry");
                                        },
                                        ledger,
                                        3,
                                        0L,
                                        () -> false,
                                        6));
        assertEquals(1, calls.get());
        assertEquals(4, ledger.getRemainingTokens());
    }

    /** 已取消和已过期的任务不会扣除从未实际使用的预算。 */
    @Test
    public void shouldNotChargeTokensBeforeStateCheck() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() + 5000L, 10);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(() -> "never", ledger, 1, 0L, () -> true, 6));
        assertEquals(10, ledger.getRemainingTokens());
    }

    /** 认证错误不是重试信号。 */
    @Test
    public void shouldNotRetryAuthenticationFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                RetryableOperationException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new RetryableOperationException(401, "auth");
                                        },
                                        new BudgetLedger(3, System.currentTimeMillis() + 5000L),
                                        3,
                                        0L,
                                        () -> false));
        assertEquals(1, calls.get());
    }

    /** 最后一个调用名额被并行争抢时，调用数与 Token 扣减必须一致。 */
    @Test
    public void shouldReserveAttemptAtomically() throws Exception {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() + 5000L, 10);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> a = pool.submit(() -> ledger.reserveAttempt(6));
            java.util.concurrent.Future<Boolean> b = pool.submit(() -> ledger.reserveAttempt(6));
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
            assertEquals(0, ledger.getRemainingModelCalls());
            assertEquals(4, ledger.getRemainingTokens());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 截止时间覆盖重试退避，不能退避后继续调用；预留量不应再次扣减。 */
    @Test
    public void shouldStopBeforeRetryWhenDeadlineReached() {
        AtomicInteger calls = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(0L);
        BudgetLedger ledger =
                new BudgetLedger(3, System.currentTimeMillis() + 5000L, 30) {
                    @Override
                    public boolean isExpired() {
                        return clock.get() >= 100;
                    }

                    @Override
                    public long remainingMillis() {
                        return 0;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            clock.set(100);
                                            throw new RetryableOperationException(
                                                    429, "rate limit");
                                        },
                                        ledger,
                                        3,
                                        1000L,
                                        () -> false,
                                        5));
        assertEquals(1, calls.get());
        assertEquals(25, ledger.getRemainingTokens());
    }

    /** 已过期任务保持所有预算未使用。 */
    @Test
    public void shouldNotChargeExpiredAttempt() {
        BudgetLedger ledger = new BudgetLedger(2, System.currentTimeMillis() - 1, 10);
        assertThrows(
                IllegalStateException.class,
                () ->
                        new HarnessCallExecutor(new FailureClassifier())
                                .execute(() -> "never", ledger, 2, 0, () -> false, 5));
        assertEquals(2, ledger.getRemainingModelCalls());
        assertEquals(10, ledger.getRemainingTokens());
    }
}
