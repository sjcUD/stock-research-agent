package io.github.sjcud.stockagent.evaluation;

import java.util.*;

/** 逐结论语义评审契约；与词法检查分开，模型评分仍需人工抽查。 */
public interface SemanticJudge {
    /** 评审完整回答（含额外结论）、遗漏事实和拒答恰当性。 */
    Result judge(String question, String answer, Map<String, String> evidence, List<String> facts);

    /** 结构化结果只含判定和可复核结论，不保存模型隐式推理。 */
    final class Result {
        /** 每条模型拆分的回答结论与引用ID。 */
        public final List<Claim> claims;
        /** 遗漏的标准事实，不能用“没有错误结论”代替完整性。 */
        public final List<String> missingFacts;
        /** 未被支持的结论数和拒答判定。 */
        public final int unsupportedClaims;
        public final boolean abstentionCorrect;
        /** 创建不可变评审结果。 */
        public Result(List<Claim> claims, List<String> missing, boolean abstention) {
            this.claims = Collections.unmodifiableList(new ArrayList<Claim>(claims));
            missingFacts = Collections.unmodifiableList(new ArrayList<String>(missing));
            abstentionCorrect = abstention;
            int unsupported = 0; for (Claim claim : claims) if (!claim.supported) unsupported++;
            unsupportedClaims = unsupported;
        }
        /** 有可审阅结论且事实完整、拒答正确才算通过。 */
        public boolean passed() { return !claims.isEmpty() && unsupportedClaims == 0 && missingFacts.isEmpty() && abstentionCorrect; }
    }
    /** 单条结论的证据判定。 */
    final class Claim {
        public final String text;
        public final boolean supported;
        public final List<String> evidenceIds;
        public Claim(String text, boolean supported, List<String> ids) {
            this.text = text; this.supported = supported;
            evidenceIds = Collections.unmodifiableList(new ArrayList<String>(ids));
        }
    }
}
