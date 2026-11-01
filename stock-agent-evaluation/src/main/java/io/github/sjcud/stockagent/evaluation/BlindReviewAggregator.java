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
        Set<Path> seenFiles = new HashSet<Path>();
        String dataset = null;
        Map<String, String> answers = new HashMap<String, String>();
        for (Path file : files) {
            if (!seenFiles.add(file.toRealPath())) throw new IllegalArgumentException("重复评审文件");
            Set<String> seenQuestions = new HashSet<String>();
            for (Row row : read(file)) {
                if (dataset == null) dataset = row.dataset;
                if (!dataset.equals(row.dataset) || !seenQuestions.add(row.questionId))
                    throw new IllegalArgumentException("数据集混用或重复评审问题");
                String identity = CandidateIdentity.fingerprint(row.dataset, row.questionId, row.question, row.answer);
                if (answers.containsKey(row.questionId) && !answers.get(row.questionId).equals(identity))
                    throw new IllegalArgumentException("同一问题的候选答案不一致");
                answers.put(row.questionId, identity); rows.add(row);
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("没有已完成评审");
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
        return new Result(dataset, answers, rows.size(), citation, faithful, rows.size() == 0 ? 0.0 : (double) citation / rows.size(),
                rows.size() == 0 ? 0.0 : (double) faithful / rows.size(), comparisons == 0 ? null : (double) agreements / comparisons);
    }

    /** 读取严格的七列盲评 CSV，空白布尔值和未知值都拒绝。 */
    private List<Row> read(Path file) throws IOException {
        if (Files.size(file) > 20L * 1024 * 1024) throw new IllegalArgumentException("盲评文件过大");
        List<List<String>> records = parseRecords(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        List<String> header = Arrays.asList("dataset", "questionId", "question", "answer", "citation_ok", "faithful", "notes");
        if (records.isEmpty() || !records.get(0).equals(header)) throw new IllegalArgumentException("盲评表头不匹配");
        List<Row> result = new ArrayList<Row>();
        for (int i = 1; i < records.size(); i++) {
            List<String> fields = records.get(i);
            if (fields.size() == 1 && fields.get(0).isEmpty()) continue;
            if (fields.size() != 7 || fields.get(1).trim().isEmpty()) throw new IllegalArgumentException("盲评记录格式错误: " + (i + 1));
            if (fields.get(0).trim().isEmpty() || fields.get(2).trim().isEmpty() || fields.get(3).trim().isEmpty())
                throw new IllegalArgumentException("评审数据集、问题或候选答案为空");
            result.add(new Row(fields.get(0), fields.get(1), fields.get(2), fields.get(3), bool(fields.get(4), i), bool(fields.get(5), i)));
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

    /** CSV状态机：支持引号内换行和双引号转义，拒绝未闭合或位置错误的引号。 */
    private List<List<String>> parseRecords(String csv) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> fields = new ArrayList<String>(); StringBuilder value = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else value.append(c);
            } else if (c == ',') { fields.add(value.toString()); value.setLength(0); closed = false; }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                fields.add(value.toString()); records.add(fields); fields = new ArrayList<String>(); value.setLength(0); closed = false;
            } else if (c == '"' && value.length() == 0 && !closed) quoted = true;
            else { if (closed || c == '"') throw new IllegalArgumentException("CSV引号位置无效"); value.append(c); }
        }
        if (quoted) throw new IllegalArgumentException("CSV引号未闭合");
        if (!fields.isEmpty() || value.length() > 0 || closed) { fields.add(value.toString()); records.add(fields); }
        return records;
    }

    private static final class Row {
        private final String dataset, questionId, question, answer; private final boolean citationOk, faithful;
        private Row(String dataset, String id, String question, String answer, boolean citation, boolean faithful) { this.dataset = dataset; questionId = id; this.question = question; this.answer = answer; citationOk = citation; this.faithful = faithful; }
    }

    /** 机器可序列化的盲评统计。 */
    public static final class Result {
        public final String dataset;
        /** 实际完成评审的候选内容指纹，不依赖模型名称。 */
        public final Map<String, String> candidateFingerprints;
        public final int totalReviews, citationPasses, faithfulPasses;
        public final double citationPassRate, faithfulPassRate;
        public final Double pairwiseAgreement;
        private Result(String dataset, Map<String, String> identities, int total, int citation, int faithful, double citationRate, double faithfulRate, Double agreement) {
            this.dataset = dataset; candidateFingerprints = Collections.unmodifiableMap(new LinkedHashMap<String, String>(identities)); totalReviews = total; citationPasses = citation; faithfulPasses = faithful;
            citationPassRate = citationRate; faithfulPassRate = faithfulRate; pairwiseAgreement = agreement;
        }
    }
}
