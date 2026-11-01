package io.github.sjcud.stockagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 生成不泄露标准答案的人工盲评表，供评审者独立填写。 */
public final class BlindReviewTemplate {
    private BlindReviewTemplate() { }

    /** 输出 CSV：仅包含问题和空白评审字段，不写入 facts 或相关文档标注。 */
    public static void write(Path target, EvaluationDataset dataset) throws IOException {
        write(target, dataset, java.util.Collections.<String, String>emptyMap());
    }

    /** 预填匿名候选回答，评审者只填写判断与备注，不接触模型名或标准答案。 */
    public static void write(Path target, EvaluationDataset dataset, java.util.Map<String, String> answers) throws IOException {
        StringBuilder csv = new StringBuilder("dataset,questionId,question,answer,citation_ok,faithful,notes\n");
        for (EvaluationDataset.Question question : dataset.getQuestions()) {
            if (!answers.isEmpty() && !answers.containsKey(question.getId())) continue;
            csv.append(escape(dataset.getVersion())).append(',')
                    .append(escape(question.getId())).append(',')
                    .append(escape(question.getText())).append(',')
                    .append(answers.containsKey(question.getId()) ? escape(answers.get(question.getId())) : "").append(",,,\n");
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV 字段转义，避免问题中的逗号、引号或换行破坏盲评表。 */
    private static String escape(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
