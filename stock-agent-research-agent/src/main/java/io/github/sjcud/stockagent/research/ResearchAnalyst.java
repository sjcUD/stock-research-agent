package io.github.sjcud.stockagent.research;

import io.github.sjcud.stockagent.data.MarketSnapshot;

/** 分析师统一能力边界；数据获取和模型调用由实现或上层注入。 */
public interface ResearchAnalyst {
    /** 基于问题、行情和共享证据生成角色结论。 */
    AnalystResult analyze(String question, MarketSnapshot snapshot, String evidence);
    /**
     * 保守估算输入及输出预留；自定义实现若增加其他上下文，应覆盖此方法。
     * 默认按字符计数，包含全部行情字段，额外预留角色规则及输出空间。
     */
    default int estimateTokens(String question, MarketSnapshot snapshot, String evidence) {
        String input = question + snapshot.getSymbol() + snapshot.getClose()
                + snapshot.getAsOf() + snapshot.getSource() + (evidence == null ? "" : evidence);
        return (int) Math.min(Integer.MAX_VALUE, (long) input.length() + 4096L);
    }
    /** 返回稳定角色标识。 */
    AnalystRole getRole();
}
