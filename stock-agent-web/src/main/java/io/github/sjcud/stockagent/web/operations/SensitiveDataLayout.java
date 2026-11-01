package io.github.sjcud.stockagent.web.operations;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.*;

/** 日志出口适配器：最终正文与异常统一脱敏，避免只处理业务logger而漏掉SDK异常。 */
public final class SensitiveDataLayout extends PatternLayout {
    /** 配置密钥按环境变量和JVM属性读取，不把变量值写回日志。 */
    private final SecretRedactor redactor;

    /** 日志系统启动早于Spring容器，必须使用启动时已存在的配置来源。 */
    public SensitiveDataLayout() {
        List<String> values = new ArrayList<String>();
        for (String prefix :
                new String[] {
                    "chat.api-key", "embedding.api-key", "qdrant.api-key", "mysql.password"
                }) {
            String property = "stock.agent." + prefix;
            values.add(
                    System.getenv(
                            property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')));
            values.add(System.getProperty(property));
        }
        redactor = new SecretRedactor(values);
    }

    /** Pattern中包含完整异常格式化，处理后才交给encoder写入控制台。 */
    @Override
    public String doLayout(ILoggingEvent event) {
        return redactor.redact(super.doLayout(event));
    }
}
