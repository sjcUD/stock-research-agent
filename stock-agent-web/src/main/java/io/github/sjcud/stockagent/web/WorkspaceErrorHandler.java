package io.github.sjcud.stockagent.web;

import java.util.Collections;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 仅将工作台已控制的错误文案回传页面，不公开底层异常或供应商响应。 */
@RestControllerAdvice(assignableTypes={WorkspaceController.class,ResearchTaskController.class})
public class WorkspaceErrorHandler {
    /** 返回控制器固定文案，保留原HTTP状态码。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handle(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Collections.singletonMap("message",exception.getReason()));
    }
}
