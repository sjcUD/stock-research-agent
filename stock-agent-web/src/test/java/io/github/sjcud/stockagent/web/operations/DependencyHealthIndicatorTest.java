package io.github.sjcud.stockagent.web.operations;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** 就绪检查必须反映真实依赖，异常详情不得包含连接参数。 */
public class DependencyHealthIndicatorTest {
    @Test
    public void shouldIgnoreDisabledDependencies() {
        assertEquals(
                Status.UP,
                new DependencyHealthIndicator(null, null, false, false).health().getStatus());
    }

    @Test
    public void shouldFailWhenEnabledDependencyIsUnavailable() {
        assertEquals(
                Status.DOWN,
                new DependencyHealthIndicator(() -> true, () -> false, false, false)
                        .health()
                        .getStatus());
    }

    @Test
    public void shouldHideProviderExceptionDetails() {
        String secret = "test-password-never-display";
        org.springframework.boot.actuate.health.Health health =
                new DependencyHealthIndicator(
                                () -> {
                                    throw new IllegalStateException(secret);
                                },
                                null,
                                false,
                                false)
                        .health();
        assertEquals(Status.DOWN, health.getStatus());
        assertFalse(health.toString().contains(secret));
    }

    @Test
    public void shouldRequireModelConfigurationOnlyWhenRequested() {
        assertEquals(
                Status.DOWN,
                new DependencyHealthIndicator(null, null, true, false).health().getStatus());
        assertEquals(
                Status.UP,
                new DependencyHealthIndicator(null, null, true, true).health().getStatus());
    }
}
