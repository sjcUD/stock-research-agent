package io.github.sjcud.stockagent.llm;

import java.util.Objects;

/**
 * 描述一种模型能力的不可变配置。
 *
 * <p>Chat、Embedding 和 Reranker 共用这个配置对象，避免业务 Agent
 * 直接读取环境变量或依赖某个供应商 SDK。Embedding 维度和批量大小
 * 在创建 profile 时校验，防止索引空间不兼容。</p>
 */
public final class ModelProfile {

    private final String capability;
    private final String provider;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    private ModelProfile(String capability, String provider, String model, int dimensions, int batchSize) {
        this.capability = requireText(capability, "capability");
        this.provider = requireText(provider, "provider");
        this.model = requireText(model, "model");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions 必须大于0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于0");
        }
        this.dimensions = dimensions;
        this.batchSize = batchSize;
    }

    /** 创建 Embedding 模型 profile。 */
    public static ModelProfile embedding(String provider, String model, int dimensions, int batchSize) {
        return new ModelProfile("embedding", provider, model, dimensions, batchSize);
    }

    /** 返回模型能力类型。 */
    public String getCapability() {
        return capability;
    }

    /** 返回供应商名称。 */
    public String getProvider() {
        return provider;
    }

    /** 返回模型名称。 */
    public String getModel() {
        return model;
    }

    /** 返回向量维度。非向量模型仍保留统一字段，便于 profile 比较。 */
    public int getDimensions() {
        return dimensions;
    }

    /** 返回单批最大输入数量。 */
    public int getBatchSize() {
        return batchSize;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelProfile)) {
            return false;
        }
        ModelProfile that = (ModelProfile) other;
        return dimensions == that.dimensions
                && batchSize == that.batchSize
                && capability.equals(that.capability)
                && provider.equals(that.provider)
                && model.equals(that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capability, provider, model, dimensions, batchSize);
    }
}
