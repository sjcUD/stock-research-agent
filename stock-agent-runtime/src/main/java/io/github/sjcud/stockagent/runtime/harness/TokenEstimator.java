package io.github.sjcud.stockagent.runtime.harness;

/** 输入按UTF8字节保守估算，避免中文/emoji用单个UTF16字符严重低估；不冒充真实tokenizer。 */
public final class TokenEstimator {
    /** 工具类不实例化。 */
    private TokenEstimator() { }
    /** 上层仍需额外加角色规则和输出预留；模型实际usage需单独结算。 */
    public static int estimate(String value){return value==null?0:value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    /** 防止预算求和溢出变成负额度。 */
    public static int add(int input,int output){return (int)Math.min(Integer.MAX_VALUE,(long)input+output);}
}
