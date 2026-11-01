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
    private int remainingCalls,remainingTokens;
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
    public boolean reserveAttempt(int tokens) {
        if(tokens<0)throw new IllegalArgumentException("tokens 必须非负");
        synchronized(gate) {
            for(BudgetLedger p=this;p!=null;p=p.parent)if(p.remainingCalls<1||p.remainingTokens<tokens)return false;
            for(BudgetLedger p=this;p!=null;p=p.parent){p.remainingCalls--;p.remainingTokens-=tokens;}return true;
        }
    }
    /** 保留独立请求次数预留接口。 */
    public boolean reserveModel(){return reserveAttempt(0);}
    /** 独立Token预留同样遵守父链。 */
    public boolean reserveTokens(int tokens) {
        if(tokens<0)throw new IllegalArgumentException("tokens 必须非负");
        synchronized(gate) {
            for(BudgetLedger p=this;p!=null;p=p.parent)if(p.remainingTokens<tokens)return false;
            for(BudgetLedger p=this;p!=null;p=p.parent)p.remainingTokens-=tokens;return true;
        }
    }
    /** 显示用途，不用变化的系统墙钟再次计算执行截止。 */
    public long getDeadlineMillis(){return deadlineMillis;}
    /** 使用差值判断，兼容nanoTime的计数回绕。 */
    public boolean isExpired(){return deadlineNanos-System.nanoTime()<=0;}
    /** 正剩余不足1ms时向上取1ms，避免HTTP库把0解释为无限等待。 */
    public long remainingMillis(){long nanos=deadlineNanos-System.nanoTime();return nanos<=0?0:Math.max(1,TimeUnit.NANOSECONDS.toMillis(nanos));}
    /** 当前阶段剩余估算Token。 */
    public int getRemainingTokens(){synchronized(gate){return remainingTokens;}}
    /** 当前阶段剩余模型调用数。 */
    public int getRemainingModelCalls(){synchronized(gate){return remainingCalls;}}
    /** 复制整棵预算树，回调和JSON序列化不持有运行锁。 */
    public Snapshot snapshot(){synchronized(gate){return copy();}}
    /** 仅在共享锁内递归复制，不暴露可变账本。 */
    private Snapshot copy(){List<Snapshot> stages=new ArrayList<Snapshot>();for(BudgetLedger c:children)stages.add(c.copy());return new Snapshot(name,callLimit,tokenLimit,callLimit-remainingCalls,tokenLimit-remainingTokens,remainingMillis(),stages);}
    /** 不可变诊断快照，可随工作台归档。 */
    public static final class Snapshot {
        /** 阶段、配置与已预留用量；不包含问题、密钥和供应商信息。 */
        public final String name;
        public final int callLimit,tokenLimit,usedCalls,estimatedTokens;
        public final long remainingMillis;
        public final List<Snapshot> stages;
        /** 构造防御性树快照。 */
        public Snapshot(String name,int calls,int tokens,int used,int estimated,long left,List<Snapshot> stages){this.name=name;callLimit=calls;tokenLimit=tokens;usedCalls=used;estimatedTokens=estimated;remainingMillis=left;this.stages=Collections.unmodifiableList(new ArrayList<Snapshot>(stages));}
        /** 返回已预留调用数。 */
        public int getUsedCalls(){return usedCalls;}
        /** 返回不可变阶段列表。 */
        public List<Snapshot> getStages(){return stages;}
    }
}
