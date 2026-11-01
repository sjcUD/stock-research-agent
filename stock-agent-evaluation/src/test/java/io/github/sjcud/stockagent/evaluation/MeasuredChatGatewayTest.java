package io.github.sjcud.stockagent.evaluation;
import static org.junit.jupiter.api.Assertions.*;
import io.github.sjcud.stockagent.llm.*;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
/** 缺失usage或价格不输出费用；失败必须计入统计分母。 */
public class MeasuredChatGatewayTest {
 @Test public void computesConfiguredRateOnlyForKnownUsage() {
  ChatModelGateway provider = new ChatModelGateway() {
   public String chat(String p) { return "ok"; }
   public ChatCompletion complete(String p) { return new ChatCompletion("ok", 100, 50); }
  };
  MeasuredChatGateway measured = new MeasuredChatGateway(provider, new BigDecimal("2"), new BigDecimal("4"), "CNY");
  measured.chat("q");
  Map<?,?> attempt = (Map<?,?>) ((List<?>) measured.report().get("attempts")).get(0);
  assertEquals(0, new BigDecimal("0.0004").compareTo((BigDecimal) attempt.get("estimatedCost")));
  MeasuredChatGateway unknown = new MeasuredChatGateway(p -> "ok", null, null, null); unknown.chat("q");
  assertNull(((Map<?,?>)((List<?>)unknown.report().get("attempts")).get(0)).get("estimatedCost"));
 }
 @Test public void retainsFailedAttemptWithoutExceptionBody() {
  MeasuredChatGateway measured = new MeasuredChatGateway(p -> {throw new IllegalStateException("private");}, null, null, null);
  assertThrows(IllegalStateException.class, () -> measured.chat("q"));
  assertEquals(1, measured.report().get("samples")); assertEquals(0, measured.report().get("successes"));
  assertFalse(measured.report().toString().contains("private"));
 }
}
