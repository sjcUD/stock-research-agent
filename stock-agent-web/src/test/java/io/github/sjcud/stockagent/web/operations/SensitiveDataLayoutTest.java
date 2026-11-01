package io.github.sjcud.stockagent.web.operations;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;

import org.junit.jupiter.api.Test;

/** 验证日志真实出口包含异常堆栈时也会执行环境配置值脱敏。 */
public class SensitiveDataLayoutTest {
    @Test
    public void shouldRedactMessageAndThrowableAtOutputBoundary() {
        String property = "stock.agent.chat.api-key";
        String original = System.getProperty(property);
        LoggerContext context = new LoggerContext();
        try {
            System.setProperty(property, "fake-layout-credential-123");
            SensitiveDataLayout layout = new SensitiveDataLayout();
            layout.setContext(context);
            layout.setPattern("%msg%n%ex");
            layout.start();
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName("test");
            event.setLevel(Level.ERROR);
            event.setMessage("model failed fake-layout-credential-123");
            event.setThrowableProxy(
                    new ThrowableProxy(new IllegalStateException("fake-layout-credential-123")));
            String output = layout.doLayout(event);
            assertTrue(output.contains("IllegalStateException"));
            assertTrue(output.contains("***"));
            assertFalse(output.contains("fake-layout-credential-123"));
            layout.stop();
        } finally {
            context.stop();
            if (original == null) System.clearProperty(property);
            else System.setProperty(property, original);
        }
    }
}
