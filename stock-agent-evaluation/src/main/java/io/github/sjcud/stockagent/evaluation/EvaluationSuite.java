package io.github.sjcud.stockagent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.sjcud.stockagent.analyst.FundamentalsAnalyst;
import io.github.sjcud.stockagent.data.MarketSnapshot;
import io.github.sjcud.stockagent.llm.EmbeddingGateway;
import io.github.sjcud.stockagent.rag.chunking.*;
import io.github.sjcud.stockagent.rag.retrieval.*;
import io.github.sjcud.stockagent.rag.store.VectorStore;
import io.github.sjcud.stockagent.research.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 可重复的离线/真实模型评测编排；供应商和存储以原业务接口注入。 */
public final class EvaluationSuite {
    /** 数据集从不可变版本加载，各配置必须独立建索引。 */
    private final EvaluationDataset dataset;

    /** 绑定本次评测所需的独立依赖与模型配置。 */
    public EvaluationSuite(EvaluationDataset dataset) {
        this.dataset = Objects.requireNonNull(dataset);
    }

    /** 18组哈希基线，只用于无需密钥的回归，不可当成真实 Embedding 质量。 */
    public List<Map<String, Object>> offline() {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (int dimensions : new int[] {32, 128})
            for (int chunk : new int[] {32, 64, 128}) {
                VectorStore index =
                        new EvaluationVectorIndex(
                                EvaluationVectorIndex.hashBaseline(dimensions), dimensions);
                index.upsert(chunks(chunk));
                for (int topK : new int[] {1, 3, 5})
                    results.add(
                            evaluate(index, "hash-bigram-NONSEMANTIC", dimensions, chunk, topK));
            }
        return results;
    }

    /** 阈值实验保持与离线基线相同的18组配置，只改变检索命中下限。 */
    public List<Map<String, Object>> offlineWithThreshold(double threshold) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        for (int dimensions : new int[] {32, 128}) for (int chunk : new int[] {32, 64, 128}) {
            VectorStore index = new EvaluationVectorIndex(EvaluationVectorIndex.hashBaseline(dimensions), dimensions);
            index.upsert(chunks(chunk));
            for (int topK : new int[] {1, 3, 5})
                results.add(evaluate(index, "hash-bigram-NONSEMANTIC", dimensions, chunk, topK, threshold));
        }
        return results;
    }

    /** 使用生产切片策略，offset 与引用回链采用同一 UTF-16 口径。 */
    public List<DocumentChunk> chunks(int size) {
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        for (EvaluationDataset.Source source : dataset.getDocuments())
            result.addAll(
                    new FixedWindowChunkingStrategy(size)
                            .chunk(source.getId(), source.getRevision(), 1, source.getText()));
        return result;
    }

    /** 每个问题均纳入分母；检索异常记为 ERROR 和0分，禁止通过漏掉失败问题抬高分数。 */
    public Map<String, Object> evaluate(
            VectorStore store, String model, int dimensions, int chunk, int topK) {
        return evaluate(store, model, dimensions, chunk, topK, Double.NEGATIVE_INFINITY);
    }

    /** 执行带相似度下限的评测；阈值写入行结果，避免报告无法复现。 */
    public Map<String, Object> evaluate(
            VectorStore store, String model, int dimensions, int chunk, int topK, double threshold) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("model", model);
        row.put("dimensions", dimensions);
        row.put("chunkSize", chunk);
        row.put("topK", topK);
        if (threshold != Double.NEGATIVE_INFINITY) row.put("similarityThreshold", threshold);
        List<Map<String, Object>> cases = new ArrayList<Map<String, Object>>();
        double recall = 0, hit = 0, mrr = 0, fp = 0;
        int positives = 0, negatives = 0, total = 0, valid = 0, errors = 0;
        RagEvaluator evaluator = new RagEvaluator(dataset);
        for (EvaluationDataset.Question q : dataset.getQuestions()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("id", q.getId());
            boolean positive = !q.getRelevant().isEmpty();
            if (positive) positives++;
            else negatives++;
            try {
                List<RetrievalHit> hits = store.search(new RetrievalQuery(q.getText(), topK));
                RagEvaluator.Score s = evaluator.score(q, hits, threshold);
                if (positive) {
                    recall += s.recall;
                    hit += s.hit;
                    mrr += s.reciprocalRank;
                } else fp += s.falsePositive;
                valid += s.validCitations;
                total += s.totalHits;
                detail.put("status", "OK");
                detail.put("score", s);
                detail.put("hits", hits);
            } catch (RuntimeException ex) {
                errors++;
                detail.put("status", "ERROR");
                detail.put("errorType", ex.getClass().getSimpleName());
                // 不记录异常消息：供应商消息可能包含地址、请求体或敏感信息。
                if (!positive) fp++;
            }
            cases.add(detail);
        }
        row.put("recallAtK", positives == 0 ? null : recall / positives);
        row.put("hitAtK", positives == 0 ? null : hit / positives);
        row.put("mrr", positives == 0 ? null : mrr / positives);
        row.put("negativeAnyHitRate", negatives == 0 ? null : fp / negatives);
        row.put("citationCoverage", total == 0 ? null : (double) valid / total);
        row.put("errors", errors);
        row.put("positiveQueries", positives);
        row.put("negativeQueries", negatives);
        row.put("cases", cases);
        return row;
    }

    /** 离线脚本模型只验证服务接线及评分器。实际轨迹从 VectorStore 调用处采集，不从期望复制。 */
    public Map<String, Object> scriptedWorkflow() {
        List<ToolCallEvaluator.Call> actual = new ArrayList<ToolCallEvaluator.Call>();
        EvaluationDataset.Source source = dataset.getDocuments().get(0);
        RetrievalHit evidence =
                new RetrievalHit(
                        "alpha:v1:1:0", source.getText(), 1, new Citation("alpha", "v1", 1, 0));
        VectorStore recordingStore =
                new VectorStore() {
                    @Override
                    public void upsert(List<DocumentChunk> chunks) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<RetrievalHit> search(RetrievalQuery query) {
                        synchronized (actual) {
                            actual.add(
                                    new ToolCallEvaluator.Call(
                                            "search", query.getText(), query.getTopK(), true));
                        }
                        return Collections.singletonList(evidence);
                    }
                };
        ResearchChatService service =
                new ResearchChatService(
                        recordingStore,
                        new FundamentalsAnalyst(
                                prompt -> {
                                    if (!prompt.contains(source.getText())
                                            || !prompt.contains("甲公司收入是多少"))
                                        throw new IllegalStateException("上下文丢失");
                                    return "样例甲公司收入为120亿元[片段=alpha:v1:1:0]。";
                                }));
        ResearchChatAnswer answer =
                service.ask(
                        "甲公司收入是多少",
                        new MarketSnapshot("SYNTHETIC", "0", "2025-12-31", "EVALUATION"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("mode", "SCRIPTED_MODEL_APPLICATION_DISPATCH_ONLY");
        result.put(
                "answerRules",
                new AnswerEvaluator()
                        .score(
                                answer.getAnswer(),
                                Collections.singletonList("120亿元"),
                                false,
                                Collections.singleton(evidence.getChunkId())));
        Map<String, String> citedEvidence = new LinkedHashMap<String, String>();
        citedEvidence.put(evidence.getChunkId(), evidence.getText());
        result.put("faithfulness",
                new AnswerEvaluator().faithfulness(answer.getAnswer(),
                        Collections.singletonList("120亿元"), citedEvidence));
        result.put(
                "toolContract",
                new ToolCallEvaluator()
                        .score(
                                Collections.singletonList(
                                        new ToolCallEvaluator.Call("search", "甲公司收入是多少", 5, true)),
                                actual));
        return result;
    }

    /** 同一个 profile 内缓存相同文本，避免 topK 对比重复付费；缓存不跨模型/维度。 */
    public static EmbeddingGateway cached(EmbeddingGateway delegate) {
        Map<String, float[]> cache = new HashMap<String, float[]>();
        return texts -> {
            List<String> missing = new ArrayList<String>();
            for (String text : texts)
                if (!cache.containsKey(text) && !missing.contains(text)) missing.add(text);
            if (!missing.isEmpty()) {
                List<float[]> vectors = delegate.embed(missing);
                if (vectors == null || vectors.size() != missing.size())
                    throw new IllegalStateException("向量数量错误");
                for (int i = 0; i < missing.size(); i++)
                    cache.put(missing.get(i), vectors.get(i).clone());
            }
            List<float[]> result = new ArrayList<float[]>();
            for (String text : texts) result.add(cache.get(text).clone());
            return result;
        };
    }

    /** 输出 JSON 机器结果与 Markdown 指标表；报告只含公开合成输入，不输出密钥和环境配置。 */
    public void writeReport(
            Path directory, String mode, List<Map<String, Object>> rows, Map<String, Object> extra)
            throws Exception {
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("dataset", dataset.getVersion());
        report.put("sha256", dataset.getSha256());
        report.put("generatedAt", Instant.now().toString());
        report.put("mode", mode);
        report.put("limitations", "合成小样本；文档级去重指标；引用可回链不等于语义支持；无阈值检索的无答案命中率可能为1；规则评分不等于投资质量。");
        report.put("configurations", rows);
        report.put("checks", extra);
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve(mode + ".json").toFile(), report);
        StringBuilder md =
                new StringBuilder(
                        "# Stock Agent 评测\n\n数据集: "
                                + dataset.getVersion()
                                + "；SHA-256: "
                                + dataset.getSha256()
                                + "\n\n");
        md.append(report.get("limitations"))
                .append(
                        "\n\n"
                                + "|模型|维度|chunk|topK|Recall|Hit|MRR|Citation|无答案任意命中率|错误|\n"
                                + "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Map<String, Object> row : rows) {
            md.append('|');
            for (String key :
                    new String[] {
                        "model",
                        "dimensions",
                        "chunkSize",
                        "topK",
                        "recallAtK",
                        "hitAtK",
                        "mrr",
                        "citationCoverage",
                        "negativeAnyHitRate",
                        "errors"
                    }) md.append(row.get(key)).append('|');
            md.append('\n');
        }
        md.append("\n其他检查见同名 JSON。每个问题与实际命中均保留；ERROR 纳入失败分母。不同报告的模式、模型、数据集哈希必须一致才可比较。\n");
        Files.write(
                directory.resolve(mode + ".md"),
                md.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
