package io.github.sjcud.stockagent.evaluation;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** 人工盲评与机器报告并列导出；不把模型裁判分数当成人工评价。 */
public final class BlindReviewReport {
    /** 验证评审数据集与机器报告一致后保存；缺少真实评审不会自动补分。 */
    public void write(Path target, Path machineReport, List<Path> reviews) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode machine = mapper.readTree(machineReport.toFile());
        if (!machine.path("dataset").isTextual() || !machine.path("sha256").isTextual())
            throw new IllegalArgumentException("缺少机器报告数据集身份");
        BlindReviewAggregator.Result human = new BlindReviewAggregator().aggregate(reviews);
        if (!machine.get("dataset").asText().equals(human.dataset)) throw new IllegalArgumentException("人工与机器报告的数据集不一致");
        JsonNode manifest = machine.path("checks").path("reviewCandidates");
        if (!manifest.isArray() || manifest.isEmpty()) throw new IllegalArgumentException("机器报告缺少候选清单，无法对照人工评分");
        Map<String, String> identities = new LinkedHashMap<String, String>();
        for (JsonNode candidate : manifest) {
            for (String key : Arrays.asList("questionId", "question", "answer"))
                if (!candidate.path(key).isTextual() || candidate.get(key).asText().trim().isEmpty()) throw new IllegalArgumentException("候选清单字段无效");
            String id = candidate.get("questionId").asText();
            if (identities.containsKey(id)) throw new IllegalArgumentException("候选清单问题重复");
            identities.put(id, CandidateIdentity.fingerprint(human.dataset, id, candidate.get("question").asText(), candidate.get("answer").asText()));
        }
        for (Map.Entry<String, String> entry : human.candidateFingerprints.entrySet())
            if (!entry.getValue().equals(identities.get(entry.getKey()))) throw new IllegalArgumentException("人工评分与机器候选不一致");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("dataset", machine.get("dataset").asText()); result.put("sha256", machine.get("sha256").asText());
        result.put("humanReview", human); result.put("machineChecks", machine.get("checks"));
        result.put("limitations", "人工文件由调用方确认独立评审；不证明匿名性，不混算人工/模型评分，成对一致率不是Cohen kappa");
        Files.createDirectories(target.toAbsolutePath().getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), result);
    }
}
