package io.github.sjcud.stockagent.llm;

import java.util.HashMap;
import java.util.Map;

/**
 * LLM 能力注册表。
 *
 * <p>注册表是一个轻量工厂入口：上层通过 profile 名称获得能力实现，
 * 真实 provider 装配由 Spring 配置完成。首版不在这里读取环境变量，
 * 也不把具体 SDK 类型暴露给研究模块。</p>
 */
public class ProviderRegistry {

    private final Map<String, ChatModelGateway> chatModels;
    private final Map<String, EmbeddingGateway> embeddingModels;
    private final Map<String, RerankGateway> rerankModels;

    /** 创建空注册表；Spring 装配阶段显式注册能力实现。 */
    public ProviderRegistry() {
        this.chatModels = new HashMap<String, ChatModelGateway>();
        this.embeddingModels = new HashMap<String, EmbeddingGateway>();
        this.rerankModels = new HashMap<String, RerankGateway>();
    }

    /** 注册 Chat 能力。重复名称被拒绝，防止配置静默覆盖。 */
    public void registerChat(String name, ChatModelGateway gateway) {
        register(name, gateway, chatModels);
    }

    /** 注册 Embedding 能力。 */
    public void registerEmbedding(String name, EmbeddingGateway gateway) {
        register(name, gateway, embeddingModels);
    }

    /** 注册 Rerank 能力。 */
    public void registerRerank(String name, RerankGateway gateway) {
        register(name, gateway, rerankModels);
    }

    /** 获取 Chat 能力；名称不存在时明确失败。 */
    public ChatModelGateway chat(String name) {
        return get(name, chatModels, "Chat");
    }

    /** 获取 Embedding 能力。 */
    public EmbeddingGateway embedding(String name) {
        return get(name, embeddingModels, "Embedding");
    }

    /** 获取 Rerank 能力。 */
    public RerankGateway rerank(String name) {
        return get(name, rerankModels, "Rerank");
    }

    private <T> void register(String name, T value, Map<String, T> target) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("能力名称不能为空");
        }
        if (value == null) {
            throw new IllegalArgumentException("能力实现不能为空");
        }
        if (target.containsKey(name)) {
            throw new IllegalArgumentException("能力名称已注册: " + name);
        }
        target.put(name, value);
    }

    private <T> T get(String name, Map<String, T> target, String capability) {
        T value = target.get(name);
        if (value == null) {
            throw new IllegalArgumentException(capability + "能力未注册: " + name);
        }
        return value;
    }
}
