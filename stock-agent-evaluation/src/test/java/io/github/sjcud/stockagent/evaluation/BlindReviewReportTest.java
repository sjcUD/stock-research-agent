package io.github.sjcud.stockagent.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 只用明确标记的测试评分校验汇总，不冒充真实评审者。 */
public class BlindReviewReportTest {
    @Test public void shouldHandleQuotedNewlinesAndCompareSameDataset() throws Exception {
        Path dir = Files.createTempDirectory("p11-review-fixture");
        Path machine = dir.resolve("machine.json"), one = dir.resolve("reviewer1.csv"), two = dir.resolve("reviewer2.csv"), output = dir.resolve("comparison.json");
        try {
            String header = "dataset,questionId,question,answer,citation_ok,faithful,notes\n";
            Map<String, Object> report = new LinkedHashMap<String, Object>(); report.put("dataset", "v1"); report.put("sha256", "test-only");
            Map<String, String> candidate = new LinkedHashMap<String, String>(); candidate.put("questionId", "q1"); candidate.put("question", "q"); candidate.put("answer", "第一行\n第二行");
            report.put("checks", Collections.singletonMap("reviewCandidates", Arrays.asList(candidate)));
            new ObjectMapper().writeValue(machine.toFile(), report);
            Files.write(one, (header + "v1,q1,q,\"第一行\n第二行\",true,true,\"a,b\"\n").getBytes("UTF-8"));
            Files.write(two, (header + "v1,q1,q,\"第一行\n第二行\",false,true,\n").getBytes("UTF-8"));
            BlindReviewAggregator.Result result = new BlindReviewAggregator().aggregate(Arrays.asList(one, two));
            assertEquals(.5, result.citationPassRate); assertEquals(1.0, result.faithfulPassRate); assertEquals(0.0, result.pairwiseAgreement);
            new BlindReviewReport().write(output, machine, Arrays.asList(one, two));
            assertEquals(2, new ObjectMapper().readTree(output.toFile()).path("humanReview").path("totalReviews").asInt());
            assertThrows(IllegalArgumentException.class, () -> new BlindReviewAggregator().aggregate(Arrays.asList(one, one)));
        } finally { for (Path path : Arrays.asList(machine, one, two, output)) Files.deleteIfExists(path); Files.deleteIfExists(dir); }
    }
    @Test public void shouldImportExplicitHumanReviewFilesWhenConfigured() throws Exception {
        String reviews = System.getProperty("stock.eval.reviews");
        if (reviews == null) return;
        List<Path> files = new ArrayList<Path>(); for (String file : reviews.split(",")) files.add(Paths.get(file));
        new BlindReviewReport().write(Paths.get(System.getProperty("stock.eval.review.output", "evaluation-reports/human-machine-comparison.json")),
                Paths.get(Objects.requireNonNull(System.getProperty("stock.eval.machine"), "stock.eval.machine 必填")), files);
    }
}
