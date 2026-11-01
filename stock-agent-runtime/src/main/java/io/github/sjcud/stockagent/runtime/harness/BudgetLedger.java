package io.github.sjcud.stockagent.runtime.harness;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** 每请求父预算与阶段子预算；同一个监视器原子校验整条父链，避免并行超支和部分扣费。 */
public class BudgetLedger {
    /** 全树共享锁与上级，根没有parent。 */
    private final Object gate;
    private final BudgetLedger parent;
    /** 固定阶段标识与配置额度；Token是预留估算，不是供应商实际usage。 */
    private final String name;
    private final int callLimit,tokenLimit;
    /** 只在gate内读写。 */
    private int remainingCalls;
    private long remainingTokens;
    /** 各次预估与实际结算分别累计，不能把未知usage计为零。 */
    private long reservedEstimates, actualTokens;
    private int settledCalls, unknownUsageCalls;
    /** 墙钟仅用于显示，执行截止用单调时钟。 */
    private final long deadlineMillis,deadlineNanos;
    /** 子阶段仅用于观测，不反向序列化parent。 */
    private final List<BudgetLedger> children=new ArrayList<BudgetLedger>();
    /** 兼容初期无Token限制的调用。 */
    public BudgetLedger(int calls,long deadlineMillis){this(calls,deadlineMillis,Integer.MAX_VALUE);}
    /** 创建独立父账本；极大墙钟日期按最多一年活动时间转换，避免纳秒溢出。 */
    public BudgetLedger(int calls,long deadlineMillis,int tokens) {
        this(null,"RUN",calls,tokens,deadlineMillis,
            System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.min(TimeUnit.DAYS.toMillis(365),Math.max(0L,deadlineMillis-System.currentTimeMillis()))));
    }
    /** 子账本继承父锁，不能拥有晚于父级的执行截止时间。 */
    private BudgetLedger(BudgetLedger parent,String name,int calls,int tokens,long millis,long nanos) {
        if(calls<0||tokens<0||millis<=0||name==null||name.trim().isEmpty())throw new IllegalArgumentException("预算配置无效");
        this.parent=parent;this.gate=parent==null?new Object():parent.gate;this.name=name;
        callLimit=calls;tokenLimit=tokens;remainingCalls=calls;remainingTokens=tokens;deadlineMillis=millis;deadlineNanos=nanos;
    }
    /** 各阶段额度限制共享父预算，不是复制一份可额外消费的余额。 */
    public BudgetLedger child(String label,int calls,int tokens,long timeoutMillis) {
        if(timeoutMillis<=0||timeoutMillis>TimeUnit.DAYS.toMillis(1))throw new IllegalArgumentException("阶段时限无效");
        synchronized(gate) {
            if(children.size()>=64)throw new IllegalStateException("阶段数量超过上限");
            long now=System.nanoTime();
            long left=Math.max(0L,Math.min(deadlineNanos-now,TimeUnit.MILLISECONDS.toNanos(timeoutMillis)));
            BudgetLedger child=new BudgetLedger(this,label,calls,tokens,System.currentTimeMillis()+Math.max(1,TimeUnit.NANOSECONDS.toMillis(left)),now+left);
            children.add(child);return child;
        }
    }
    /** 原子校验整个父链；任意一层不足时一层也不扣。 */
    public boolean reserveAttempt(int tokens) { return reserveAttemptWithReceipt(tokens) != null; }
    /** 返回本次预留凭据，结算只作用于这次尝试，不改变已消费调用次数。 */
    public Reservation reserveAttemptWithReceipt(int tokens) {
        if(tokens<0)throw new IllegalArgumentException("tokens 必须非负");
        synchronized(gate) {
            for(BudgetLedger p=this;p!=null;p=p.parent)if(p.remainingCalls<1||p.remainingTokens<tokens)return null;
            for(BudgetLedger p=this;p!=null;p=p.parent){p.remainingCalls--;p.remainingTokens-=tokens;p.reservedEstimates+=tokens;}
            return new Reservation(this,tokens);
        }
    }
    /** 任一上级实际超支时阻止后续执行。 */
    public boolean isOverdrawn() {
        synchronized(gate) {for(BudgetLedger p=this;p!=null;p=p.parent)if(p.remainingTokens<0)return true;return false;}
    }
    /** 一次尝试的结算凭据；共享gate保证并发阶段不能相互退款。 */
    public static final class Reservation {
        private final BudgetLedger owner;
        private final int reserved;
        private boolean actualSettled, unknown;
        /** 只由原子预留方法创建，防止凭空增加余额。 */
        private Reservation(BudgetLedger owner,int reserved){this.owner=owner;this.reserved=reserved;}
        /** 已知usage替换本次预留；超支如实扣成负数，不伪造“供应商没有收费”。 */
        public void settleActual(long actual) {
            if(actual<0||actual>2L*Integer.MAX_VALUE)throw new IllegalArgumentException("实际usage无效");
            synchronized(owner.gate) {
                if(actualSettled)throw new IllegalStateException("本次模型尝试已结算");
                for(BudgetLedger p=owner;p!=null;p=p.parent){p.remainingTokens+=(long)reserved-actual;p.actualTokens+=actual;p.settledCalls++;if(unknown)p.unknownUsageCalls--;}
                actualSettled=true;unknown=false;
            }
        }
        /** 无usage或失败时保留预估；取消后迟到的真实usage仍可补记，不能少记费用。 */
        public void markUnknown(){synchronized(owner.gate){if(!actualSettled&&!unknown){for(BudgetLedger p=owner;p!=null;p=p.parent)p.unknownUsageCalls++;unknown=true;}}}
    }
    /** 保留独立请求次数预留接口。 */
    public boolean reserveModel(){return reserveAttempt(0);}
    /** 独立Token预留同样遵守父链。 */
    public boolean reserveTokens(int tokens) {
        if(tokens<0)throw new IllegalArgumentException("tokens 必须非负");
        synchronized(gate) {
            for(BudgetLedger p=this;p!=null;p=p.parent)if(p.remainingTokens<tokens)return false;
            for(BudgetLedger p=this;p!=null;p=p.parent){p.remainingTokens-=tokens;p.reservedEstimates+=tokens;}return true;
        }
    }
    /** 显示用途，不用变化的系统墙钟再次计算执行截止。 */
    public long getDeadlineMillis(){return deadlineMillis;}
    /** 使用差值判断，兼容nanoTime的计数回绕。 */
    public boolean isExpired(){return deadlineNanos-System.nanoTime()<=0;}
    /** 正剩余不足1ms时向上取1ms，避免HTTP库把0解释为无限等待。 */
    public long remainingMillis(){long nanos=deadlineNanos-System.nanoTime();return nanos<=0?0:Math.max(1,TimeUnit.NANOSECONDS.toMillis(nanos));}
    /** 当前阶段剩余估算Token。 */
    public int getRemainingTokens(){synchronized(gate){return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,remainingTokens));}}
    /** 当前阶段剩余模型调用数。 */
    public int getRemainingModelCalls(){synchronized(gate){return remainingCalls;}}
    /** 复制整棵预算树，回调和JSON序列化不持有运行锁。 */
    public Snapshot snapshot(){synchronized(gate){return copy();}}
    /** 仅在共享锁内递归复制，不暴露可变账本。 */
    private Snapshot copy(){
        List<Snapshot> stages=new ArrayList<Snapshot>();for(BudgetLedger c:children)stages.add(c.copy());
        return new Snapshot(name,callLimit,tokenLimit,callLimit-remainingCalls,(int)Math.min(Integer.MAX_VALUE,reservedEstimates),remainingMillis(),stages,
                actualTokens,settledCalls,unknownUsageCalls,(long)tokenLimit-remainingTokens);
    }
    /** 不可变诊断快照，可随工作台归档。 */
    public static final class Snapshot {
        /** 阶段、配置与已预留用量；不包含问题、密钥和供应商信息。 */
        public final String name;
        public final int callLimit,tokenLimit,usedCalls,estimatedTokens;
        public final long remainingMillis;
        /** 实际已知usage、已结算调用数与未知usage调用数。 */
        public final long actualTokens, chargedTokens;
        public final int settledCalls, unknownUsageCalls;
        public final List<Snapshot> stages;
        /** 构造防御性树快照。 */
        public Snapshot(String name,int calls,int tokens,int used,int estimated,long left,List<Snapshot> stages){this(name,calls,tokens,used,estimated,left,stages,0L,0,0,estimated);}
        /** 附带真实结算观测；chargedTokens为已知实际加未知预留，不等同账单金额。 */
        private Snapshot(String name,int calls,int tokens,int used,int estimated,long left,List<Snapshot> stages,long actual,int settled,int unknown,long charged){
            this.name=name;callLimit=calls;tokenLimit=tokens;usedCalls=used;estimatedTokens=estimated;remainingMillis=left;
            this.stages=Collections.unmodifiableList(new ArrayList<Snapshot>(stages));actualTokens=actual;settledCalls=settled;unknownUsageCalls=unknown;chargedTokens=charged;
        }
        /** 返回已预留调用数。 */
        public int getUsedCalls(){return usedCalls;}
        /** 返回不可变阶段列表。 */
        public List<Snapshot> getStages(){return stages;}
    }
}
