package io.github.sjcud.stockagent.runtime.harness;

/** 阶段/请求截止导致的终止，不是可无限重试的网络异常。 */
public final class HarnessTimeoutException extends IllegalStateException {
    /** 对用户安全的超时提示。 */
    public HarnessTimeoutException(){super("研究阶段达到配置的截止时间，请检查服务或调整时限后重试。");}
}
