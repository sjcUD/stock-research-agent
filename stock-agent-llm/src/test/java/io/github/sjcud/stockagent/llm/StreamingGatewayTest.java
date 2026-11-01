package io.github.sjcud.stockagent.llm;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;

import reactor.core.publisher.Flux;

import java.util.*;

/** 必须从真实StreamingChatModel通路收到多个delta，不得先chat再切字伪装流式。 */
public class StreamingGatewayTest {
    @Test
    public void usesStreamingModelAndSettlesUsageOnlyOnce() {
        List<String> chunks = new ArrayList<String>();
        List<Integer> usage = new ArrayList<Integer>();
        class Model implements ChatModel, StreamingChatModel {
            public ChatResponse call(org.springframework.ai.chat.prompt.Prompt p) {
                throw new AssertionError("不应走非流式call");
            }

            public Flux<ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt p) {
                return Flux.just(
                        new ChatResponse(
                                Arrays.asList(new Generation(new AssistantMessage("Hello")))),
                        new ChatResponse(
                                Arrays.asList(new Generation(new AssistantMessage(" world"))),
                                ChatResponseMetadata.builder()
                                        .usage(new DefaultUsage(5, 2))
                                        .build()));
            }
        }
        ChatCompletion result =
                new SpringAiChatModelGateway(
                                new Model(), (input, output) -> usage.add(input + output))
                        .completeStreaming("q", chunks::add);
        assertEquals(Arrays.asList("Hello", " world"), chunks);
        assertEquals("Hello world", result.getText());
        assertEquals(Arrays.asList(7), usage);
    }

    @Test
    public void deadlineMustCancelUpstreamRatherThanPublishPartialSuccess() {
        java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        class Model implements ChatModel, StreamingChatModel {
            public ChatResponse call(org.springframework.ai.chat.prompt.Prompt p) {
                throw new AssertionError();
            }

            public Flux<ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt p) {
                return Flux.just(
                                new ChatResponse(
                                        Arrays.asList(
                                                new Generation(new AssistantMessage("partial")))))
                        .concatWith(Flux.never())
                        .doOnCancel(() -> cancelled.set(true));
            }
        }
        assertThrows(
                RuntimeException.class,
                () ->
                        new SpringAiChatModelGateway(new Model(), (a, b) -> {}, () -> 50L)
                                .completeStreaming("q", text -> {}));
        assertTrue(cancelled.get());
    }
}
