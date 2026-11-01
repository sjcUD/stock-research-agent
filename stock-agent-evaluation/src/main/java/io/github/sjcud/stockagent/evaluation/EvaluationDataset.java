package io.github.sjcud.stockagent.evaluation;

import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 版本化合成语料及人工标注；检索器只能读取文档，不能读取问题的标准答案。 */
public final class EvaluationDataset {
    /** 数据集版本与内容哈希用于比较报告是否来自同一语料。 */
    private final String version, sha256;

    /** 不可变语料与问题，避免评测时被检索适配器污染。 */
    private final List<Source> documents;

    private final List<Question> questions;

    private EvaluationDataset(
            String version, String hash, List<Source> documents, List<Question> questions) {
        this.version = version;
        this.sha256 = hash;
        this.documents = Collections.unmodifiableList(documents);
        this.questions = Collections.unmodifiableList(questions);
    }

    /** 从随源码版本控制的 UTF-8 JSON 加载，拒绝重复 ID 和悬空相关文档标注。 */
    public static EvaluationDataset load() throws Exception {
        byte[] bytes;
        try (InputStream in =
                EvaluationDataset.class.getResourceAsStream(
                        "/evaluation/stock-agent-synthetic-v1.json")) {
            if (in == null) throw new IllegalStateException("缺少固定评测数据集");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        JsonNode json = new ObjectMapper().readTree(new String(bytes, StandardCharsets.UTF_8));
        Set<String> ids = new HashSet<String>();
        List<Source> docs = new ArrayList<Source>();
        for (JsonNode node : json.required("documents")) {
            String id = required(node, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("重复文档 ID");
            docs.add(new Source(id, required(node, "revision"), required(node, "text")));
        }
        Set<String> queryIds = new HashSet<String>();
        List<Question> qs = new ArrayList<Question>();
        for (JsonNode node : json.required("questions")) {
            String id = required(node, "id");
            if (!queryIds.add(id)) throw new IllegalArgumentException("重复问题 ID");
            Set<String> relevant =
                    new LinkedHashSet<String>(strings(node.required("relevantDocuments")));
            if (!ids.containsAll(relevant)) throw new IllegalArgumentException("相关文档不存在");
            qs.add(
                    new Question(
                            id, required(node, "text"), relevant, strings(node.required("facts"))));
        }
        if (docs.isEmpty() || qs.isEmpty()) throw new IllegalArgumentException("空数据集");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format("%02x", b & 255));
        return new EvaluationDataset(required(json, "version"), hash.toString(), docs, qs);
    }

    /** 强制必要的文本标注非空。 */
    private static String required(JsonNode node, String key) {
        String value = node.required(key).asText();
        if (value.trim().isEmpty()) throw new IllegalArgumentException("空字段 " + key);
        return value;
    }

    /** 读取 JSON 数组；数组之外的结构一律拒绝。 */
    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) throw new IllegalArgumentException("标注必须为数组");
        List<String> values = new ArrayList<String>();
        for (JsonNode value : node) values.add(value.asText());
        return values;
    }

    /** 返回不可变评测标注或配置。 */
    public String getVersion() {
        return version;
    }

    /** 返回不可变评测标注或配置。 */
    public String getSha256() {
        return sha256;
    }

    /** 返回不可变评测标注或配置。 */
    public List<Source> getDocuments() {
        return documents;
    }

    /** 返回不可变评测标注或配置。 */
    public List<Question> getQuestions() {
        return questions;
    }

    /** 单篇原文，引用验证以原文 UTF-16 偏移为准；页码固定为合成文本第1页。 */
    public static final class Source {
        /** 稳定标识、版本和不可变原文。 */
        private final String id, revision, text;

        private Source(String id, String revision, String text) {
            this.id = id;
            this.revision = revision;
            this.text = text;
        }

        /** 返回不可变评测标注或配置。 */
        public String getId() {
            return id;
        }

        /** 返回不可变评测标注或配置。 */
        public String getRevision() {
            return revision;
        }

        /** 返回不可变评测标注或配置。 */
        public String getText() {
            return text;
        }
    }

    /** 单个检索/回答问题；空相关集合表示无答案，不参与正例召回率分母。 */
    public static final class Question {
        /** 人工标注，与模型或向量索引隔离。 */
        private final String id, text;

        private final Set<String> relevant;
        private final List<String> facts;

        private Question(String id, String text, Set<String> relevant, List<String> facts) {
            this.id = id;
            this.text = text;
            this.relevant = Collections.unmodifiableSet(relevant);
            this.facts = Collections.unmodifiableList(facts);
        }

        /** 返回不可变评测标注或配置。 */
        public String getId() {
            return id;
        }

        /** 返回不可变评测标注或配置。 */
        public String getText() {
            return text;
        }

        /** 返回不可变评测标注或配置。 */
        public Set<String> getRelevant() {
            return relevant;
        }

        /** 返回不可变评测标注或配置。 */
        public List<String> getFacts() {
            return facts;
        }
    }
}
