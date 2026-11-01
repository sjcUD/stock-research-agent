package io.github.sjcud.stockagent.web.operations;

import org.springframework.boot.actuate.health.*;

import java.util.function.Supplier;

/** 就绪依赖聚合器；探测由装配层注入，状态输出不包含密钥、URL或供应商异常。 */
public final class DependencyHealthIndicator implements HealthIndicator {
    /** null表示未启用；启用的检查必须真实执行，不以配置存在代替连通。 */
    private final Supplier<Boolean> qdrantProbe, mysqlProbe;

    /** 模型仅检查配置完整性，健康轮询不产生收费推理调用。 */
    private final boolean requireModels, modelsConfigured;

    /** 依赖探针与配置要求分别注入，便于测试故障和禁用状态。 */
    public DependencyHealthIndicator(
            Supplier<Boolean> qdrantProbe,
            Supplier<Boolean> mysqlProbe,
            boolean requireModels,
            boolean modelsConfigured) {
        this.qdrantProbe = qdrantProbe;
        this.mysqlProbe = mysqlProbe;
        this.requireModels = requireModels;
        this.modelsConfigured = modelsConfigured;
    }

    /** 外部依赖异常只影响就绪，不应影响只检查进程存活的liveness。 */
    @Override
    public Health health() {
        String qdrant = probe(qdrantProbe), mysql = probe(mysqlProbe);
        boolean up =
                !"DOWN".equals(qdrant)
                        && !"DOWN".equals(mysql)
                        && (!requireModels || modelsConfigured);
        return (up ? Health.up() : Health.down())
                .withDetail("qdrant", qdrant)
                .withDetail("mysql", mysql)
                .withDetail(
                        "modelConfiguration",
                        modelsConfigured
                                ? "CONFIGURED_NOT_PROBED"
                                : requireModels ? "MISSING" : "NOT_REQUIRED")
                .build();
    }

    /** 禁用、成功、失败分开，不把异常堆栈放入公开健康响应。 */
    private String probe(Supplier<Boolean> probe) {
        if (probe == null) return "DISABLED";
        try {
            return Boolean.TRUE.equals(probe.get()) ? "UP" : "DOWN";
        } catch (RuntimeException ex) {
            return "DOWN";
        }
    }
}
