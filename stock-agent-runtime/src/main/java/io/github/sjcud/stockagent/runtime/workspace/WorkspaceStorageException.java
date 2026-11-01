package io.github.sjcud.stockagent.runtime.workspace;

/** 存储不可用的独立异常类型，控制器只回传固定文案，不暴露连接信息。 */
public final class WorkspaceStorageException extends RuntimeException {
    /** 保留内部因果供受控诊断，不把供应商信息拼到公开消息。 */
    public WorkspaceStorageException(Throwable cause) {
        super("会话存储暂不可用", cause);
    }
}
