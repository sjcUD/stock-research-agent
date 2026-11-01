package io.github.sjcud.stockagent.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 读取人工盲评 CSV，输出可复核的引用和忠实度统计，不读取数据集标准答案。 */
public final class BlindReviewAggregator {
    /** 聚合一个或多个评审者文件；文件顺序只用于稳定统计，不代表评审权重。 */
    public Result aggregate(List<Path> files) throws IOException {
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("盲评文件不能为空");
        List<Row> rows = new ArrayList<Row>();
        for (Path file : files) rows.addAll(read(file));
        int citation = 0, faithful = 0;
        for (Row row : rows) { if (row.citationOk) citation++; if (row.faithful) faithful++; }
        Map<String, List<Row>> byQuestion = new LinkedHashMap<String, List<Row>>();
        for (Row row : rows) {
            List<Row> same = byQuestion.get(row.questionId);
            if (same == null) { same = new ArrayList<Row>(); byQuestion.put(row.questionId, same); }
            same.add(row);
        }
        int comparisons = 0, agreements = 0;
        for (List<Row> same : byQuestion.values()) for (int i = 0; i < same.size(); i++)
            for (int j = i + 1; j < same.size(); j++) {
                comparisons++;
                if (same.get(i).citationOk == same.get(j).citationOk
                        && same.get(i).faithful == same.get(j).faithful) agreements++;
            }
        return new Result(rows.size(), citation, faithful, rows.size() == 0 ? 0.0 : (double) citation / rows.size(),
                rows.size() == 0 ? 0.0 : (double) faithful / rows.size(), comparisons == 0 ? null : (double) agreements / comparisons);
    }

    /** 读取严格的七列盲评 CSV，空白布尔值和未知值都拒绝。 */
    private List<Row> read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("dataset,questionId,question,answer,citation_ok,faithful,notes"))
            throw new IllegalArgumentException("盲评表头不匹配: " + file);
        List<Row> result = new ArrayList<Row>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            List<String> fields = parse(lines.get(i));
            if (fields.size() != 7 || fields.get(1).trim().isEmpty()) throw new IllegalArgumentException("盲评行格式错误: " + (i + 1));
            result.add(new Row(fields.get(1), bool(fields.get(4), i), bool(fields.get(5), i)));
        }
        return result;
    }

    /** 只接受 true/false、yes/no 或 1/0，避免把评审者拼写误差统计成通过。 */
    private boolean bool(String value, int line) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "yes".equals(v) || "1".equals(v)) return true;
        if ("false".equals(v) || "no".equals(v) || "0".equals(v)) return false;
        throw new IllegalArgumentException("盲评布尔值无效，行 " + (line + 1));
    }

    /** 支持双引号和 CSV 双引号转义的最小解析器。 */
    private List<String> parse(String line) {
        List<String> fields = new ArrayList<String>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < line.length(); i++) { char c = line.charAt(i);
            if (c == '"') { if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; } else quoted = !quoted; }
            else if (c == ',' && !quoted) { fields.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        if (quoted) throw new IllegalArgumentException("盲评 CSV 引号未闭合");
        fields.add(value.toString()); return fields;
    }

    private static final class Row {
        private final String questionId; private final boolean citationOk, faithful;
        private Row(String id, boolean citation, boolean faithful) { questionId = id; citationOk = citation; this.faithful = faithful; }
    }

    /** 机器可序列化的盲评统计。 */
    public static final class Result {
        public final int totalReviews, citationPasses, faithfulPasses;
        public final double citationPassRate, faithfulPassRate;
        public final Double pairwiseAgreement;
        private Result(int total, int citation, int faithful, double citationRate, double faithfulRate, Double agreement) {
            totalReviews = total; citationPasses = citation; faithfulPasses = faithful;
            citationPassRate = citationRate; faithfulPassRate = faithfulRate; pairwiseAgreement = agreement;
        }
    }
}
