package io.github.sjcud.stockagent.web.operations;

import java.util.*;
import java.util.regex.Pattern;

/** 日志脱敏纯函数：先替换已配置秘密原值，再处理常见认证头与键值字段。 */
public final class SecretRedactor {
    /** 先替换较长值，避免重叠密钥只替换前缀导致尾部泄露。 */
    private final List<String> secrets;

    /** 兼容纯文本、JSON和常见HTTP头数组表示；保留字段名便于诊断。 */
    private static final Pattern AUTH =
            Pattern.compile(
                    "(?i)(\\bauthorization[\\x22']?\\s*[:=]\\s*[\\[\\x22']*\\s*(?:bearer\\s+)?)([^\\s\\x22',;}\\]]+)");

    private static final Pattern FIELD =
            Pattern.compile(
                    "(?i)(\\b(?:api[-_]?key|password|secret|access[-_]?token)[\\x22']?\\s*[:=]\\s*[\\x22']?)([^\\s\\x22',;}]+)");

    /** 创建不可变规则，不读取文件、不修改业务请求或模型输入。 */
    public SecretRedactor(Collection<String> values) {
        List<String> copy = new ArrayList<String>();
        for (String value : values) if (value != null && !value.isEmpty()) copy.add(value);
        copy.sort(Comparator.comparingInt(String::length).reversed());
        secrets = Collections.unmodifiableList(copy);
    }

    /** 对完整格式化日志处理，因此多行异常同样经过规则。 */
    public String redact(String message) {
        if (message == null) return null;
        String result = message;
        for (String secret : secrets) result = result.replace(secret, "***");
        result = AUTH.matcher(result).replaceAll("$1***");
        return FIELD.matcher(result).replaceAll("$1***");
    }
}
