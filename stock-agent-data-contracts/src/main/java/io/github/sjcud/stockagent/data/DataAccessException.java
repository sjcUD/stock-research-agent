package io.github.sjcud.stockagent.data;

/** 数据不可用异常；核心行情失败时中止 LIVE 请求，不回退伪装样例。 */
public class DataAccessException extends RuntimeException {
    /** 保留内部原因，公开错误信息不包含供应商原始响应。 */
    public DataAccessException(String message, Throwable cause) { super(message, cause); }
}
