package io.github.sjcud.stockagent.runtime.harness;

/** 将 HTTP/供应商状态转换为有限恢复策略使用的分类。 */
public class FailureClassifier {
    /** 401/403/参数类错误不重试，5xx/429 只允许有限重试。 */
    public FailureClass classify(int statusCode) {
        if (statusCode == 401 || statusCode == 403 || statusCode == 400 || statusCode == 422) {
            return FailureClass.PERMANENT;
        }
        if (statusCode == 429 || statusCode >= 500 || statusCode < 0) {
            return FailureClass.RETRYABLE;
        }
        return FailureClass.UNKNOWN;
    }
}
