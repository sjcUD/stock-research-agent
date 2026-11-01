package io.github.sjcud.stockagent.web;

import io.github.sjcud.stockagent.runtime.workspace.WorkspaceRepository;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 工作台历史查询没有模型副作用；会话随首次入队事务创建，避免空会话和孤立消息。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    /** 历史和实时任务共享同一个归档来源。 */
    private final ResearchTaskService tasks;

    /** 注入工作台协调器。 */
    public ConversationController(ResearchTaskService tasks) {
        this.tasks = tasks;
    }

    /** 仅加载分页元数据，完整报告在用户打开会话时再加载。 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        try {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("storage", tasks.durable() ? "MYSQL" : "MEMORY");
            result.put("conversations", tasks.conversations(limit, offset));
            result.put("limit", limit);
            result.put("offset", offset);
            return result;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "历史分页参数无效");
        }
    }

    /** 返回每轮原问题、资料范围、任务状态、事件与完整结果/轨迹。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable("id") String id) {
        validateId(id);
        try {
            return Collections.<String, Object>singletonMap("turns", tasks.history(id));
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
    }

    /** 独立消息列表，助手消息包含失败状态而非伪装成成功回答。 */
    @GetMapping("/{id}/messages")
    public List<WorkspaceRepository.Message> messages(@PathVariable("id") String id) {
        validateId(id);
        List<WorkspaceRepository.Message> values = tasks.messages(id);
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        return values;
    }

    /** 会话ID是服务端认可的UUID，不接受任意本地路径或查询表达式。 */
    private void validateId(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话ID无效");
        }
    }
}
