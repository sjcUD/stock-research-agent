package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Harness 的预算、重试、超时和取消边界。 */
public class HarnessCallExecutorTest {

    @Test
    public void shouldRetryRetryableFailureWithinBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BudgetLedger ledger = new BudgetLedger(2, System.currentTimeMillis() + 5000L);
        String result = new HarnessCallExecutor(new FailureClassifier()).execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetryableOperationException(503, "temporary");
            }
            return "ok";
        }, ledger, 2, 1L, () -> false);

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    public void shouldStopWhenCancelled() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() + 5000L);

        assertThrows(IllegalStateException.class, () -> new HarnessCallExecutor(new FailureClassifier())
                .execute(() -> "never", ledger, 1, 0L, () -> true));
    }

    @Test
    public void shouldStopWhenDeadlineExpired() {
        BudgetLedger ledger = new BudgetLedger(1, System.currentTimeMillis() - 1L);

        assertThrows(IllegalStateException.class, () -> new HarnessCallExecutor(new FailureClassifier())
                .execute(() -> "never", ledger, 1, 0L, () -> false));
    }
}
