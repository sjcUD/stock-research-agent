package io.github.sjcud.stockagent.runtime.harness;

/**
 * 最小上下文保护策略。
 *
 * <p>当前用长度截断展示算法，先保证证券、日期和 citation 行不丢失；L5
 * 会替换为完整 tool group 裁剪和摘要策略。</p>
 */
public class ContextPolicy {
    /** 保留前三行研究身份与引用，再截断过长历史。 */
    public String prepare(String context, int maxCharacters) {
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }
        if (maxCharacters <= 0) {
            throw new IllegalArgumentException("maxCharacters 必须大于0");
        }
        String[] lines = context.split("\\n");
        StringBuilder protectedPart = new StringBuilder();
        StringBuilder remainder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i < 3) {
                protectedPart.append(lines[i]).append('\n');
            } else {
                remainder.append(lines[i]).append('\n');
            }
        }
        String prefix = protectedPart.toString();
        int remaining = Math.max(0, maxCharacters - prefix.length());
        if (remainder.length() <= remaining) {
            return prefix + remainder;
        }
        return prefix + remainder.substring(0, remaining);
    }
}
