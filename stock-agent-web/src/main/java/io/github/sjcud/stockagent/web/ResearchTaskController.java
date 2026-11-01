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

    /** 局部重跑只接受角色与调度方式，问题/事实/旧报告均由原任务归档提供。 */
    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResearchTaskService.TaskView rerun(@PathVariable("id") String id, @RequestBody RerunRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("缺少重跑请求");
            return tasks.rerun(id, request.getRoles(), request.getMode());
        } catch (NoSuchElementException ex) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源任务不存在"); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能基于已完成市场研究选择不重复角色重跑，不可改写问题或证据"); }
        catch (RejectedExecutionException ex) { throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "研究队列已满，请稍后重试"); }
    }

    /** roles为空数组时只执行经理；不接受任何客户端旧报告或新题字段。 */
    public static final class RerunRequest {
        /** 明确要重跑的角色；空数组表示只更新经理。 */
        private java.util.List<io.github.sjcud.stockagent.research.AnalystRole> roles;
        /** 选中角色的串行/并行方式。 */
        private io.github.sjcud.stockagent.research.ResearchMode mode = io.github.sjcud.stockagent.research.ResearchMode.PARALLEL;
        /** 返回选中角色。 */
        public java.util.List<io.github.sjcud.stockagent.research.AnalystRole> getRoles() { return roles; }
        /** 设置选中角色，服务层验证空值与重复。 */
        public void setRoles(java.util.List<io.github.sjcud.stockagent.research.AnalystRole> roles) { this.roles = roles; }
        /** 返回调度方式。 */
        public io.github.sjcud.stockagent.research.ResearchMode getMode() { return mode; }
        /** 设置调度方式。 */
        public void setMode(io.github.sjcud.stockagent.research.ResearchMode mode) { this.mode = mode; }
        /** 显式拒绝未知字段，避免用户以为“换题后复用旧事实”有效。 */
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String field, com.fasterxml.jackson.databind.JsonNode value) { throw new IllegalArgumentException("局部重跑不支持字段：" + field); }
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
