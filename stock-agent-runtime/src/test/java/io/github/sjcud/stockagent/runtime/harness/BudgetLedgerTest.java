package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 验证父预算的预留与结算不会让并发分支重复消费最后额度。 */
public class BudgetLedgerTest {

    @Test
    public void shouldAllowOnlyOneReservationForLastModelCall() {
        BudgetLedger ledger = new BudgetLedger(1, 1000L);

        assertTrue(ledger.reserveModel());
        assertFalse(ledger.reserveModel());
    }
}
