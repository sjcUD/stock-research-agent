package io.github.sjcud.stockagent.runtime.harness;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 子阶段和父请求预算必须一次原子预留，不能失败后只扣掉一侧。 */
public class StageBudgetTest {
    @Test public void shouldReserveBothLevelsOrNeither() {
        BudgetLedger root=new BudgetLedger(2,System.currentTimeMillis()+5000,10);
        BudgetLedger a=root.child("A",1,6,5000),b=root.child("B",1,6,5000);
        assertTrue(a.reserveAttempt(6));assertFalse(b.reserveAttempt(6));
        assertEquals(1,b.getRemainingModelCalls());assertEquals(6,b.getRemainingTokens());assertEquals(4,root.getRemainingTokens());
        assertTrue(b.reserveAttempt(4));assertEquals(0,root.getRemainingModelCalls());
        assertEquals(2,root.snapshot().getUsedCalls());assertEquals(2,root.snapshot().getStages().size());
    }
    @Test public void shouldCapStageDeadlineAtParent() {
        BudgetLedger root=new BudgetLedger(1,System.currentTimeMillis()+200,20);
        BudgetLedger child=root.child("child",1,20,5000);
        assertTrue(child.remainingMillis()<=200);
        BudgetLedger expired=new BudgetLedger(1,System.currentTimeMillis()-1,20);
        assertTrue(expired.child("expired",1,20,1000).isExpired());
    }
    @Test public void shouldShareAtomicParentAcrossConcurrentStages() throws Exception {
        BudgetLedger root=new BudgetLedger(1,System.currentTimeMillis()+5000,10);
        BudgetLedger a=root.child("A",1,10,5000),b=root.child("B",1,10,5000);
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> first=pool.submit(()->a.reserveAttempt(10));
            java.util.concurrent.Future<Boolean> second=pool.submit(()->b.reserveAttempt(10));
            assertEquals(1,(first.get()?1:0)+(second.get()?1:0));assertEquals(0,root.getRemainingTokens());
        }finally{pool.shutdownNow();}
    }
}
