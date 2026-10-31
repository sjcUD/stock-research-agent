package io.github.sjcud.stockagent.analyst;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 创建首个单分析师 ReactAgent。
 *
 * <p>工厂只负责框架装配，不负责 provider 配置和研究业务状态。后续四类
 * 分析师会沿用同一工厂边界，通过不同 instruction、tool callbacks 和输出
 * schema 组合，而不是复制多个手写循环。</p>
 */
public class SingleAnalystGraphFactory {

    /**
     * 创建一个尚未绑定外部数据工具的基本分析 Agent。
     *
     * @param model Spring AI ChatModel，由独立 LLM 模块提供
     * @return 已编译的 Alibaba ReactAgent
     */
    public ReactAgent create(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("model 不能为空");
        }
        return ReactAgent.builder()
                .name("fundamentals-analyst")
                .description("学习项目中的基本面分析角色")
                .model(model)
                .instruction("基于已提供的结构化事实生成基本面分析；缺少数据时明确说明。")
                .build();
    }
}
