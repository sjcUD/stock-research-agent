package io.github.sjcud.stockagent.research;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.runtime.harness.BudgetLedger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 局部重跑必须减少真实模型调用，保留未选报告，并走实际条件边。 */
public class GraphPartialRerunTest {
 private final MarketSnapshot market=new MarketSnapshot("600519","1","2026-10-30","SAMPLE");
 @Test public void selectedRoleRerunMustReuseOtherReportsAndRunManagerOnce() throws Exception {
  AtomicInteger roles=new AtomicInteger(),managers=new AtomicInteger();
  GraphResearchWorkflow graph=new GraphResearchWorkflow(DefaultAnalystFactory.create(p->"report-"+roles.incrementAndGet()),new PortfolioManager(p->"manager-"+managers.incrementAndGet()));
  WorkflowResult original=graph.execute("q",market,"e",ResearchMode.PARALLEL,budget(6),ResearchProgressListener.NONE);
  WorkflowResult next=graph.executeSelected("q",market,"e",ResearchMode.PARALLEL,budget(2),ResearchProgressListener.NONE,
    original.getDecision().getAnalystResults(),EnumSet.of(AnalystRole.NEWS));
  assertEquals(6,roles.get());assertEquals(2,managers.get());assertEquals(5,next.getDecision().getAnalystResults().size());
  assertEquals(Arrays.asList("NEWS"),next.getState().get("executedRoles"));assertEquals(4,((List<?>)next.getState().get("reusedRoles")).size());
  for(AnalystResult prior:original.getDecision().getAnalystResults())if(prior.getRole()!=AnalystRole.NEWS)
    assertEquals(prior.getConclusion(),next.getDecision().getAnalystResults().stream().filter(r->r.getRole()==prior.getRole()).findFirst().get().getConclusion());
 }
 @Test public void emptySelectionMustRouteDirectlyToManager() throws Exception {
  AtomicInteger roles=new AtomicInteger();GraphResearchWorkflow graph=new GraphResearchWorkflow(DefaultAnalystFactory.create(p->{roles.incrementAndGet();return "report";}),new PortfolioManager(p->"manager"));
  WorkflowResult original=graph.execute("q",market,"e",ResearchMode.SERIAL,budget(6),ResearchProgressListener.NONE);
  WorkflowResult next=graph.executeSelected("q",market,"e",ResearchMode.PARALLEL,budget(1),ResearchProgressListener.NONE,original.getDecision().getAnalystResults(),EnumSet.noneOf(AnalystRole.class));
  assertEquals(5,roles.get());assertEquals("REUSE_ONLY",next.getState().get("routeTaken"));assertTrue(((List<?>)next.getState().get("executedRoles")).isEmpty());
  assertThrows(IllegalArgumentException.class,()->graph.executeSelected("q",market,"changed",ResearchMode.SERIAL,budget(2),ResearchProgressListener.NONE,original.getDecision().getAnalystResults(),EnumSet.of(AnalystRole.RISK)));
 }
 @Test public void sameWorkflowMustIsolateTwoConcurrentRequests() throws Exception {
  CountDownLatch both=new CountDownLatch(2);
  ResearchAnalyst role=new PromptResearchAnalyst(AnalystRole.RISK,"risk",p->{both.countDown();try{assertTrue(both.await(2,TimeUnit.SECONDS));}catch(InterruptedException ex){throw new IllegalStateException(ex);}return p;});
  GraphResearchWorkflow graph=new GraphResearchWorkflow(Arrays.asList(role),new PortfolioManager(p->"manager"));ExecutorService callers=Executors.newFixedThreadPool(2);
  try{
   Future<WorkflowResult> a=callers.submit(()->graph.execute("FIRST_ONLY",market,"e",ResearchMode.SERIAL,budget(2),ResearchProgressListener.NONE));
   Future<WorkflowResult> b=callers.submit(()->graph.execute("SECOND_ONLY",market,"e",ResearchMode.SERIAL,budget(2),ResearchProgressListener.NONE));
   assertFalse(a.get(4,TimeUnit.SECONDS).getDecision().getAnalystResults().get(0).getConclusion().contains("SECOND_ONLY"));
   assertFalse(b.get(4,TimeUnit.SECONDS).getDecision().getAnalystResults().get(0).getConclusion().contains("FIRST_ONLY"));
  }finally{callers.shutdownNow();}
 }
 @Test public void failedSelectedRoleMustNotFallBackToOldReportOrRunManager() throws Exception {
  java.util.concurrent.atomic.AtomicBoolean fail=new java.util.concurrent.atomic.AtomicBoolean();AtomicInteger managers=new AtomicInteger();
  GraphResearchWorkflow graph=new GraphResearchWorkflow(DefaultAnalystFactory.create(p->{if(fail.get())throw new IllegalStateException("expected failure");return "prior";}),new PortfolioManager(p->{managers.incrementAndGet();return "manager";}));
  WorkflowResult original=graph.execute("q",market,"e",ResearchMode.PARALLEL,budget(6),ResearchProgressListener.NONE);fail.set(true);
  assertThrows(Exception.class,()->graph.executeSelected("q",market,"e",ResearchMode.PARALLEL,budget(2),ResearchProgressListener.NONE,original.getDecision().getAnalystResults(),EnumSet.of(AnalystRole.NEWS)));
  assertEquals(1,managers.get());assertEquals(5,original.getDecision().getAnalystResults().size());
 }
 private BudgetLedger budget(int calls){return new BudgetLedger(calls,System.currentTimeMillis()+5000,60000);}
}
