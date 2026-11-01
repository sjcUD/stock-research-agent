package io.github.sjcud.stockagent.runtime.harness;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 实际usage只能结算自己的预留，父/子账本必须同步，失败或未知usage不能被当作免费。 */
public class BudgetSettlementTest {
 @Test public void settlesActualUsageAtomicallyAcrossParentAndChild() {
  BudgetLedger root=new BudgetLedger(3,System.currentTimeMillis()+5000,1000),stage=root.child("S",3,800,5000);
  BudgetLedger.Reservation receipt=stage.reserveAttemptWithReceipt(400);
  receipt.settleActual(120);
  assertEquals(880,root.getRemainingTokens());assertEquals(680,stage.getRemainingTokens());
  assertEquals(120L,root.snapshot().actualTokens);assertEquals(1,root.snapshot().settledCalls);
  assertThrows(IllegalStateException.class,()->receipt.settleActual(120));
 }
 @Test public void unknownUsageRetainsReservationButLateActualCanUpgradeIt() {
  BudgetLedger root=new BudgetLedger(2,System.currentTimeMillis()+5000,1000);
  BudgetLedger.Reservation receipt=root.reserveAttemptWithReceipt(400);receipt.markUnknown();receipt.markUnknown();
  assertEquals(600,root.getRemainingTokens());assertEquals(1,root.snapshot().unknownUsageCalls);
  receipt.settleActual(100);assertEquals(900,root.getRemainingTokens());assertEquals(0,root.snapshot().unknownUsageCalls);
 }
 @Test public void overrunMustBlockFurtherCallsWithoutLosingActualCharge() {
  BudgetLedger root=new BudgetLedger(2,System.currentTimeMillis()+5000,500);
  root.reserveAttemptWithReceipt(100).settleActual(600);
  assertTrue(root.isOverdrawn());assertFalse(root.reserveAttempt(1));assertEquals(600L,root.snapshot().actualTokens);
 }
 @Test public void concurrentReceiptsMustNotRefundOtherAttempts() throws Exception {
  BudgetLedger root=new BudgetLedger(10,System.currentTimeMillis()+5000,1000);java.util.List<BudgetLedger.Reservation> receipts=new java.util.ArrayList<BudgetLedger.Reservation>();
  for(int i=0;i<10;i++)receipts.add(root.reserveAttemptWithReceipt(100));
  java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
  try{
   java.util.List<java.util.concurrent.Future<?>> completed=new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for(BudgetLedger.Reservation receipt:receipts)completed.add(pool.submit(()->receipt.settleActual(50)));
   for(java.util.concurrent.Future<?> future:completed)future.get();
   assertEquals(500,root.getRemainingTokens());assertEquals(500L,root.snapshot().actualTokens);assertEquals(10,root.snapshot().settledCalls);assertEquals(0,root.getRemainingModelCalls());
  }finally{pool.shutdownNow();}
 }

}
