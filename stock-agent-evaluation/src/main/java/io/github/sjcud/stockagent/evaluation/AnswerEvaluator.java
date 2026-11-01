package io.github.sjcud.stockagent.evaluation;

import java.util.*;
import java.util.regex.*;

/** 离线回答规则检查：关键事实、拒答词和实际引用；不冒充语义忠实度或投资质量评分。 */
public final class AnswerEvaluator {
    /** 明确引用语法，未知片段不得视为可用来源。 */
    private static final Pattern CITATION = Pattern.compile("\\[片段=([^\\]]+)\\]");

    /** facts 为人工必含字符串；无答案用例必须表达资料不足；来源来自本次实际命中。 */
    public Score score(
            String answer, List<String> facts, boolean unanswerable, Set<String> allowedIds) {
        String text = answer == null ? "" : answer;
        boolean factsPresent = true;
        for (String fact : facts) factsPresent &= text.contains(fact);
        boolean abstains =
                text.contains("资料不足")
                        || text.contains("无法判断")
                        || text.contains("缺少数据")
                        || text.contains("未提供")
                        || text.contains("无法确定");
        int cited = 0, invalid = 0;
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            cited++;
            if (!allowedIds.contains(matcher.group(1))) invalid++;
        }
        boolean citations = invalid == 0 && (unanswerable || cited > 0);
        return new Score(factsPresent, !unanswerable || abstains, citations, invalid, cited);
    }

    /** 各规则独立输出，方便区分事实漏答与引用格式错误。 */
    public static final class Score {
        /** 规则命中，不代表不存在其他幻觉。 */
        public final boolean factsPresent, abstentionCorrect, citationsValid;

        /** 引用计数，未知引用单列。 */
        public final int invalidCitations, citationCount;

        private Score(boolean f, boolean a, boolean c, int invalid, int count) {
            factsPresent = f;
            abstentionCorrect = a;
            citationsValid = c;
            invalidCitations = invalid;
            citationCount = count;
        }

        /** 所有明确规则同时满足才算通过，不包含未实现的语义判断。 */
        public boolean passed() {
            return factsPresent && abstentionCorrect && citationsValid;
        }
    }
}
