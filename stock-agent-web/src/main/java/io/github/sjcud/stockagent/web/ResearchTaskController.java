package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.data.LiveStockDataProvider;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;

/** 真实任务创建、查询与取消 API，页面断线后可使用同一个ID恢复轮询。 */
@RestController
@RequestMapping("/api/research/tasks")
public class ResearchTaskController {
    /** 应用拥有的任务协调器。 */
    private final ResearchTaskService tasks;

    /** 注入任务服务。 */
    public ResearchTaskController(ResearchTaskService tasks) {
        this.tasks = tasks;
    }

    /** 输入先校验，成功入队才返回202，避免自动重试产生重复模型费用。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResearchTaskService.TaskView start(
            @RequestBody ResearchTaskService.TaskRequest request) {
        try {
            if (request == null
                    || request.getQuestion() == null
                    || request.getQuestion().trim().isEmpty()
                    || request.getQuestion().length() > 4000
                    || request.getScope() == null
                    || request.getMode() == null
                    || request.getEngine() == null
                    || (request.getScope() == ResearchTaskService.Scope.DOCUMENTS
                        && request.getEngine() != io.github.sjcud.stockagent.research.ResearchEngine.JAVA_EXECUTOR)) {
                throw new IllegalArgumentException();
            }
            request.setSymbol(LiveStockDataProvider.normalizeSymbol(request.getSymbol()));
            LocalDate.parse(request.getAsOf());
            return tasks.start(request);
        } catch (IllegalArgumentException | DateTimeException | NullPointerException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "请填写有效问题、沪深代码和研究日期", exception);
        } catch (RejectedExecutionException exception) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "研究队列已满，请稍后重试", exception);
        }
    }

    /** 轮询服务端状态，不产生新研究。 */
    @GetMapping("/{id}")
    public ResearchTaskService.TaskView get(@PathVariable("id") String id) {
        try {
            return tasks.get(id);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在", exception);
        }
    }

    /** 显式发送后端取消请求，已完成任务保留成功结果。 */
    @DeleteMapping("/{id}")
    public ResearchTaskService.TaskView cancel(@PathVariable("id") String id) {
        try {
            return tasks.cancel(id);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在", exception);
        }
    }
}
