package io.github.sjcud.stockagent.runtime.harness;

/** 带供应商状态码的可分类运行时异常。 */
public class RetryableOperationException extends RuntimeException {
    private final int statusCode;

    public RetryableOperationException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
