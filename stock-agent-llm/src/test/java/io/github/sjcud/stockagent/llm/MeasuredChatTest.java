package io.github.sjcud.stockagent.llm;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import java.util.*;
/** 供应商usage与模型文本解耦，缺失usage不能伪装为零费用。 */
public class MeasuredChatTest {
 @Test public void shouldReturnProviderUsageAndKeepLegacyCall() {
  ChatModel model = prompt -> new ChatResponse(Arrays.asList(new Generation(new AssistantMessage("ok"))), ChatResponseMetadata.builder().usage(new DefaultUsage(12, 7)).build());
  ChatCompletion result = new SpringAiChatModelGateway(model).complete("q");
  assertEquals("ok", result.getText()); assertEquals(Integer.valueOf(12), result.getInputTokens()); assertEquals(Integer.valueOf(7), result.getOutputTokens());
 }
 @Test public void lambdaGatewayMustReportUnknownUsage() {
  ChatModelGateway fake = prompt -> "ok";
  assertNull(fake.complete("q").getInputTokens());
 }
}
