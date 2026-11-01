package io.github.sjcud.stockagent.web;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.llm.*;
import io.github.sjcud.stockagent.runtime.harness.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import java.util.*;

/** 通过实际Spring配置回填usage；只看模型返回值不能证明账本真的结算。 */
public class ResearchUsageBudgetTest {
 @Test public void shouldSettleProviderUsageInHarnessWorker() throws Exception {
  ChatModel model=p->new ChatResponse(Arrays.asList(new Generation(new AssistantMessage("ok"))),ChatResponseMetadata.builder().usage(new DefaultUsage(20,10)).build());
  ChatModelGateway gateway=new ChatModelProviderConfiguration().chatModelGateway(model);
  BudgetLedger root=new BudgetLedger(1,System.currentTimeMillis()+3000,1000);
  assertEquals("ok",new HarnessCallExecutor(new FailureClassifier()).execute(()->gateway.chat("q"),root,1,0,()->false,500));
  assertEquals(970,root.getRemainingTokens());assertEquals(30L,root.snapshot().actualTokens);assertEquals(1,root.snapshot().settledCalls);
 }
 @Test public void shouldStopAfterActualOverrunAndRejectHiddenSecondCall() {
  ChatModel model=p->new ChatResponse(Arrays.asList(new Generation(new AssistantMessage("ok"))),ChatResponseMetadata.builder().usage(new DefaultUsage(200,100)).build());
  ChatModelGateway gateway=new ChatModelProviderConfiguration().chatModelGateway(model);
  BudgetLedger root=new BudgetLedger(2,System.currentTimeMillis()+3000,200);
  assertThrows(BudgetExceededException.class,()->new HarnessCallExecutor(new FailureClassifier()).execute(()->gateway.chat("q"),root,1,0,()->false,100));
  assertEquals(300L,root.snapshot().actualTokens);assertFalse(root.reserveModel());
  BudgetLedger separate=new BudgetLedger(2,System.currentTimeMillis()+3000,1000);
  assertThrows(IllegalStateException.class,()->new HarnessCallExecutor(new FailureClassifier()).execute(()->{gateway.chat("q");return gateway.chat("q2");},separate,1,0,()->false,500));
  assertEquals(1,separate.snapshot().usedCalls);
 }
 @Test public void lateProviderStartMustRecheckSharedOverrunBeforeSdk() throws Exception {
  java.util.concurrent.atomic.AtomicInteger sdkCalls=new java.util.concurrent.atomic.AtomicInteger();
  ChatModel model=p->{sdkCalls.incrementAndGet();return new ChatResponse(Arrays.asList(new Generation(new AssistantMessage("late"))));};
  ChatModelGateway gateway=new ChatModelProviderConfiguration().chatModelGateway(model);
  BudgetLedger root=new BudgetLedger(3,System.currentTimeMillis()+5000,500);
  BudgetLedger.Reservation first=root.reserveAttemptWithReceipt(100);
  java.util.concurrent.CountDownLatch reserved=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.ExecutorService caller=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{
   java.util.concurrent.Future<String> later=caller.submit(()->new HarnessCallExecutor(new FailureClassifier()).execute(()->{reserved.countDown();release.await();return gateway.chat("q");},root,1,0,()->false,100));
   assertTrue(reserved.await(2,java.util.concurrent.TimeUnit.SECONDS));first.settleActual(600);release.countDown();
   java.util.concurrent.ExecutionException failure=assertThrows(java.util.concurrent.ExecutionException.class,()->later.get(2,java.util.concurrent.TimeUnit.SECONDS));
   assertTrue(failure.getCause() instanceof BudgetExceededException);assertEquals(0,sdkCalls.get());
  }finally{release.countDown();caller.shutdownNow();}
 }

 @Test public void invalidNegativeMetadataMustRetainUnknownReservation() {
  ChatModel model=p->new ChatResponse(Arrays.asList(new Generation(new AssistantMessage("ok"))),ChatResponseMetadata.builder().usage(new DefaultUsage(-10,20)).build());
  ChatModelGateway gateway=new ChatModelProviderConfiguration().chatModelGateway(model);BudgetLedger root=new BudgetLedger(1,System.currentTimeMillis()+3000,1000);
  assertThrows(IllegalArgumentException.class,()->new HarnessCallExecutor(new FailureClassifier()).execute(()->gateway.chat("q"),root,1,0,()->false,500));
  assertEquals(500,root.getRemainingTokens());assertEquals(0L,root.snapshot().actualTokens);assertEquals(1,root.snapshot().unknownUsageCalls);
 }

}
