package io.github.sjcud.stockagent.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
/** 独立审查反例：输入标注不能默默空化，人工评分必须对应机器同一候选。 */
public class P11ReviewRegressionTest {
 @Test public void shouldRejectNonTextFactsAndWrongEvidenceType() throws Exception {
  String base="{\"version\":\"v\",\"documents\":[{\"id\":\"d\",\"revision\":\"v\",\"text\":\"fact\"}],\"questions\":[{\"id\":\"q\",\"text\":\"q\",\"relevantDocuments\":[\"d\"],\"facts\":FACTS,\"evidence\":EVIDENCE}]}";
  for (String json : Arrays.asList(base.replace("FACTS", "[{}]").replace("EVIDENCE", "{}"), base.replace("FACTS", "[\"fact\"]").replace("EVIDENCE", "[]"))) {
   Path f=Files.createTempFile("bad-eval", ".json");try{Files.write(f,json.getBytes("UTF-8"));assertThrows(IllegalArgumentException.class,()->EvaluationDataset.load(f));}finally{Files.delete(f);}
  }
 }
 @Test public void comparisonWithoutMachineCandidateManifestMustFail() throws Exception {
  Path dir=Files.createTempDirectory("candidate-mismatch"); Path machine=dir.resolve("m.json"),csv=dir.resolve("r.csv"),out=dir.resolve("o.json");
  try{Files.write(machine,"{\"dataset\":\"v1\",\"sha256\":\"test\",\"checks\":{}}".getBytes("UTF-8"));Files.write(csv,"dataset,questionId,question,answer,citation_ok,faithful,notes\nv1,q1,q,unrelated,true,true,\n".getBytes("UTF-8"));assertThrows(IllegalArgumentException.class,()->new BlindReviewReport().write(out,machine,Arrays.asList(csv)));}
  finally{Files.deleteIfExists(machine);Files.deleteIfExists(csv);Files.deleteIfExists(out);Files.deleteIfExists(dir);}
 }
}
