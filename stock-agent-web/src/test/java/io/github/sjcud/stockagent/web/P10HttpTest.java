package io.github.sjcud.stockagent.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import io.github.sjcud.stockagent.rag.document.*;
import io.github.sjcud.stockagent.rag.parser.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 通过真实HTTP连接验证SSE首块先于完成、取消、归档预览及HTML安全边界。 */
public class P10HttpTest {
    @TempDir Path directory;

    @Test
    public void streamsDraftBeforeCompletionAndKeepsOriginalAcrossRestart() throws Exception {
        CountDownLatch emitted = new CountDownLatch(1), release = new CountDownLatch(1);
        ResearchTaskService tasks =
                new ResearchTaskService(
                        (request, progress) -> {
                            progress.onProgress("ANSWER", "", "STARTED");
                            progress.onTextDelta("第一段真实回调");
                            emitted.countDown();
                            release.await();
                            progress.onTextDelta("第二段");
                            return "最终回答";
                        });
        SpringApplication app = new SpringApplication(StockAgentWebApplication.class);
        app.addInitializers(
                context -> {
                    RootBeanDefinition fixture =
                            new RootBeanDefinition(ResearchTaskService.class, () -> tasks);
                    fixture.setPrimary(true);
                    ((BeanDefinitionRegistry) context.getBeanFactory())
                            .registerBeanDefinition("p10Tasks", fixture);
                });
        try (ServletWebServerApplicationContext context =
                (ServletWebServerApplicationContext)
                        app.run(
                                "--server.port=0",
                                "--server.shutdown=immediate",
                                "--stock.agent.chat.api-key=",
                                "--stock.agent.qdrant.enabled=false",
                                "--stock.agent.data.mode=SAMPLE",
                                "--stock.agent.rag.ocr.enabled=false",
                                "--stock.agent.rag.documents.directory=" + directory)) {
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            ResearchTaskService.TaskRequest request = new ResearchTaskService.TaskRequest();
            request.setQuestion("问题");
            request.setSymbol("600519");
            request.setAsOf("2026-10-30");
            String id = tasks.start(request).getId();
            assertTrue(emitted.await(2, TimeUnit.SECONDS));
            HttpURLConnection stream = connection(base + "/api/research/tasks/" + id + "/events");
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    stream.getInputStream(), StandardCharsets.UTF_8))) {
                String snapshot = readSnapshot(reader);
                JsonNode draft = new ObjectMapper().readTree(snapshot);
                assertEquals("RUNNING", draft.path("status").asText());
                assertTrue(draft.path("draftAnswer").asText().contains("第一段"));
                release.countDown();
                JsonNode done = draft;
                for (int i = 0; i < 10 && !"COMPLETED".equals(done.path("status").asText()); i++)
                    done = new ObjectMapper().readTree(readSnapshot(reader));
                assertEquals("COMPLETED", done.path("status").asText());
                assertEquals("最终回答", done.path("result").asText());
                assertTrue(done.path("version").asLong() > draft.path("version").asLong());
            } finally {
                stream.disconnect();
            }
            DocumentRepository documents = context.getBean(DocumentRepository.class);
            byte[] html = "<script>alert('x')</script>原文".getBytes(StandardCharsets.UTF_8);
            documents.stage(new ParsedDocument("doc", "v1", "原文"), html, "text/html");
            documents.indexed("doc", "v1", 1);
            HttpURLConnection page =
                    connection(base + "/api/rag/documents/doc/revisions/v1/pages/1");
            try (InputStream input = page.getInputStream()) {
                assertEquals("原文", new ObjectMapper().readTree(input).path("text").asText());
            } finally {
                page.disconnect();
            }
            HttpURLConnection file = connection(base + "/api/rag/documents/doc/revisions/v1/file");
            try (InputStream input = file.getInputStream()) {
                assertEquals("application/octet-stream", file.getContentType());
                assertTrue(file.getHeaderField("Content-Disposition").startsWith("attachment"));
                assertEquals("nosniff", file.getHeaderField("X-Content-Type-Options"));
                assertArrayEquals(html, input.readAllBytes());
            } finally {
                file.disconnect();
            }
            assertEquals(
                    "INDEXED",
                    new FileDocumentRepository(directory, 268435456, 200)
                            .find("doc", "v1")
                            .getStatus());
            HttpURLConnection missing =
                    connection(base + "/api/rag/documents/old/revisions/v0/pages/1");
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();
        } finally {
            release.countDown();
            tasks.close();
        }
    }

    /** 心跳不作为正文快照，EOF必须显式失败，避免测试把断流当成功。 */
    private String readSnapshot(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null)
            if (line.startsWith("data:") && line.contains("draftAnswer"))
                return line.substring(5).trim();
        throw new EOFException("缺少任务快照");
    }

    private HttpURLConnection connection(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        return c;
    }
}
