package io.github.sjcud.stockagent.runtime.harness;

/** 将阶段剩余时间传到同一调用线程的HTTP传输；显式绑定，绝不污染复用线程。 */
public final class CallDeadline {
    /** 只保存单调时间，不存用户输入和密钥。 */
    private static final ThreadLocal<Long> DEADLINE=new ThreadLocal<Long>();
    /** 工具类不实例化。 */
    private CallDeadline(){ }
    /** 嵌套作用域只能缩短时限，close恢复原值。 */
    public static Scope bind(long millis) {
        if(millis<=0)throw new HarnessTimeoutException();
        Long previous=DEADLINE.get();long now=System.nanoTime();long duration=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        if(previous!=null)duration=Math.min(duration,previous-now);
        if(duration<=0)throw new HarnessTimeoutException();DEADLINE.set(now+duration);return new Scope(previous);
    }
    /** HTTP请求不能晚于阶段截止，未绑定时使用传输默认配置。 */
    public static long boundedMillis(long configured) {
        if(configured<=0)throw new IllegalArgumentException("HTTP超时必须大于0");Long value=DEADLINE.get();if(value==null)return configured;
        long delta=value-System.nanoTime();if(delta<=0)throw new HarnessTimeoutException();
        return Math.min(configured,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(delta)));
    }
    /** 可关闭绑定，支持Java8 try-with-resources语法。 */
    public static final class Scope implements AutoCloseable {
        /** 上一层值，保证嵌套调用结束后恢复。 */
        private final Long previous;
        private Scope(Long previous){this.previous=previous;}
        /** 清理线程局部变量，避免后一个请求继承过期截止。 */
        @Override public void close(){if(previous==null)DEADLINE.remove();else DEADLINE.set(previous);}
    }
}
