package io.github.sjcud.stockagent.evaluation;

import java.util.*;

/** 比较期望与实际工具轨迹；当前应用为确定性调度，不能据此声称模型自主选工具准确。 */
public final class ToolCallEvaluator {
    /** 一对一匹配避免重复调用虚增召回，参数和结果成功状态必须完全一致。 */
    public Score score(List<Call> expected, List<Call> actual) {
        List<Call> remaining = new ArrayList<Call>(expected);
        int matched = 0;
        for (Call call : actual) {
            int i = remaining.indexOf(call);
            if (i >= 0) {
                matched++;
                remaining.remove(i);
            }
        }
        double precision =
                actual.isEmpty() ? (expected.isEmpty() ? 1 : 0) : (double) matched / actual.size();
        double recall = expected.isEmpty() ? 1 : (double) matched / expected.size();
        return new Score(precision, recall, expected.equals(actual));
    }

    /** 当前 search 工具的标准轨迹，后续可由真实 ToolCallback 适配产生。 */
    public static final class Call {
        /** 名称、查询参数、topK 和是否成功，均从实际执行处采集。 */
        private final String name, query;

        private final int topK;
        private final boolean success;

        public Call(String name, String query, int topK, boolean success) {
            this.name = Objects.requireNonNull(name);
            this.query = Objects.requireNonNull(query);
            this.topK = topK;
            this.success = success;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Call)) return false;
            Call c = (Call) other;
            return name.equals(c.name)
                    && query.equals(c.query)
                    && topK == c.topK
                    && success == c.success;
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, query, topK, success);
        }
    }

    /** 精确契约匹配分数及完整顺序一致性。 */
    public static final class Score {
        /** 精确率、召回率与调和均值。 */
        public final double precision, recall, f1;

        /** 同时发现漏调、多调及顺序错误。 */
        public final boolean exactSequence;

        private Score(double p, double r, boolean exact) {
            precision = p;
            recall = r;
            f1 = p + r == 0 ? 0 : 2 * p * r / (p + r);
            exactSequence = exact;
        }
    }
}
