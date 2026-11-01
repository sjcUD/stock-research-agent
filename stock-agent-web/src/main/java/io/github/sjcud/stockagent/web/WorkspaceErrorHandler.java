package io.github.sjcud.stockagent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Map;

/** 仅将工作台已控制的错误文案回传页面，不公开底层异常或供应商响应。 */
@RestControllerAdvice(
        assignableTypes = {
            WorkspaceController.class,
            ResearchTaskController.class,
            ConversationController.class
        })
public class WorkspaceErrorHandler {
    /** 返回控制器固定文案，保留原HTTP状态码。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Collections.singletonMap("message", exception.getReason()));
    }

    /** 数据库故障明确返回503，不暴露SQL、URL、账号或底层异常正文。 */
    @ExceptionHandler(io.github.sjcud.stockagent.runtime.workspace.WorkspaceStorageException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(503)
                .body(Collections.singletonMap("message", "历史存储暂不可用，请稍后查询原任务；不要直接重复发送研究。"));
    }
}
