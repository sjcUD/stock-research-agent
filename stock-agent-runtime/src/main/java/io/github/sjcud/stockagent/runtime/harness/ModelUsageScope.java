package io.github.sjcud.stockagent.runtime.harness;

/** 工作线程中的模型尝试作用域，LLM通过中立监听器回报usage，不依赖runtime模块。 */
public final class ModelUsageScope implements AutoCloseable {
    /** 每个实际执行线程单独绑定，关闭时恢复嵌套调用上下文。 */
    private static final ThreadLocal<ModelUsageScope> CURRENT = new ThreadLocal<ModelUsageScope>();
    private final ModelUsageScope previous;
    private final BudgetLedger.Reservation reservation;
    private final BudgetLedger ledger;
    private final java.util.function.BooleanSupplier cancelled;
    private boolean invoked;
    private ModelUsageScope(BudgetLedger.Reservation reservation,BudgetLedger ledger,java.util.function.BooleanSupplier cancelled){previous=CURRENT.get();this.reservation=reservation;this.ledger=ledger;this.cancelled=cancelled;CURRENT.set(this);}
    /** 绑定已经原子预留的本次尝试。 */
    public static ModelUsageScope bind(BudgetLedger.Reservation reservation,BudgetLedger ledger){return bind(reservation,ledger,()->false);}
    /** 将同一个运行取消信号传到SDK发送前，避免action准备期间状态已变化。 */
    public static ModelUsageScope bind(BudgetLedger.Reservation reservation,BudgetLedger ledger,java.util.function.BooleanSupplier cancelled){return new ModelUsageScope(reservation,ledger,cancelled);}
    /** 同一预留不允许隐藏第二次provider调用；必须在SDK发送前检查。 */
    public static void beforeModelCall(){
        ModelUsageScope scope=CURRENT.get();if(scope==null)return;
        if(Thread.currentThread().isInterrupted()||scope.cancelled.getAsBoolean())throw new IllegalStateException("模型调用已取消");
        if(scope.ledger.isExpired())throw new HarnessTimeoutException();
        if(scope.ledger.isOverdrawn())throw new BudgetExceededException();
        if(scope.invoked)throw new IllegalStateException("一次Harness尝试只允许一个模型请求");scope.invoked=true;
    }
    /** null保留预估，已知usage原子替换预留，实际超支立即终止后续流程。 */
    public static void record(Integer input,Integer output){
        ModelUsageScope scope=CURRENT.get();if(scope==null)return;
        if(input==null||output==null){scope.reservation.markUnknown();return;}
        if(input<0||output<0)throw new IllegalArgumentException("供应商usage必须非负");
        scope.reservation.settleActual((long)input+output);
        if(scope.ledger.isOverdrawn())throw new BudgetExceededException();
    }
    /** 不覆盖真实usage；即使provider没有实现监听也会保留预估。 */
    @Override public void close(){reservation.markUnknown();if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
