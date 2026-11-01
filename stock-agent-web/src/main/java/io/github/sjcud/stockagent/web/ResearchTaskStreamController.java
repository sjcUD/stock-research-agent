package io.github.sjcud.stockagent.web;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** SSE只观察既有任务，不创建、重放或因浏览器断开取消收费研究。 */
@RestController
public final class ResearchTaskStreamController {
    private final ResearchTaskService tasks;

    public ResearchTaskStreamController(ResearchTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping(
            value = "/api/research/tasks/{id}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable("id") String id, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(190000L);
        AtomicReference<AutoCloseable> subscription = new AtomicReference<AutoCloseable>();
        Runnable release =
                () -> {
                    AutoCloseable handle = subscription.getAndSet(null);
                    if (handle != null)
                        try {
                            handle.close();
                        } catch (Exception ignored) {
                        }
                };
        emitter.onCompletion(release);
        emitter.onTimeout(
                () -> {
                    release.run();
                    emitter.complete();
                });
        emitter.onError(error -> release.run());
        try {
            AutoCloseable handle =
                    tasks.watch(
                            id,
                            view -> {
                                try {
                                    if (view == null)
                                        emitter.send(
                                                SseEmitter.event().name("heartbeat").data("{}"));
                                    else {
                                        emitter.send(
                                                SseEmitter.event()
                                                        .id(Long.toString(view.getVersion()))
                                                        .name("snapshot")
                                                        .data(view));
                                        if (!"RUNNING".equals(view.getStatus())
                                                && !"QUEUED".equals(view.getStatus()))
                                            emitter.complete();
                                    }
                                } catch (Exception ex) {
                                    release.run();
                                    emitter.completeWithError(new IllegalStateException("增量连接已关闭"));
                                    throw new IllegalStateException("增量连接已关闭");
                                }
                            },
                            emitter::complete);
            subscription.set(handle);
            return emitter;
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        } catch (RejectedExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "增量连接已满，请使用任务查询");
        }
    }
}
