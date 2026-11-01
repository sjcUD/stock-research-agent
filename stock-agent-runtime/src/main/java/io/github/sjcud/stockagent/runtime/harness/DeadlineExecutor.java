package io.github.sjcud.stockagent.runtime.harness;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 执行有界阶段；取消/超时关闭自有执行器，HTTP在工作线程读取同一截止时间。 */
public final class DeadlineExecutor {
    /** 不将阶段超时伪装成模型成功；用户取消不依赖未来结果返回。 */
    public <T>T call(Callable<T> action,BudgetLedger budget,BooleanSupplier cancelled)throws Exception {
        check(budget,cancelled);
        ExecutorService executor=Executors.newSingleThreadExecutor();Future<T> future=null;
        try {
            future=executor.submit(()->{try(CallDeadline.Scope ignored=CallDeadline.bind(budget.remainingMillis())){check(budget,cancelled);return action.call();}});
            while(true) {
                check(budget,cancelled);
                try {T result=future.get(Math.min(50,budget.remainingMillis()),TimeUnit.MILLISECONDS);check(budget,cancelled);return result;}
                catch(TimeoutException timeout){check(budget,cancelled);}
                catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new IllegalStateException("阶段未完成");}
            }
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw ex;}
        finally{if(future!=null)future.cancel(true);executor.shutdownNow();}
    }
    /** 取消和单调截止统一在执行前、等待时和发布前检查。 */
    private void check(BudgetLedger budget,BooleanSupplier cancelled){
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new IllegalStateException("研究任务已取消");
        if(budget.isExpired()||budget.remainingMillis()<=0)throw new HarnessTimeoutException();
    }
}
