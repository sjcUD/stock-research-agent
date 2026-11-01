package io.github.sjcud.stockagent.runtime.workspace;

import java.util.List;

/** 工作台归档契约：事务保存一轮消息与任务状态，运行时无需依赖HTTP或JSON框架。 */
public interface WorkspaceRepository extends AutoCloseable {
    /** 原子建立会话（不存在时）、任务及一对用户/助手消息。 */
    void create(Conversation conversation, Entry entry);

    /** 仅活动任务可更新；终态竞争失败返回false，禁止取消后发布迟到结果。 */
    boolean save(Entry entry, String assistantContent);

    /** 获取归档任务，不存在时抛NoSuchElementException。 */
    Entry get(String id);

    /** 按更新时间降序分页，limit范围1到100，offset非负。 */
    List<Conversation> conversations(int limit, int offset);

    /** 查询一段会话全部轮次；单会话最多100轮，防止无限上下文和响应。 */
    List<Entry> entries(String conversationId);

    /** 查询用户与助手消息；状态来自对应任务。 */
    List<Message> messages(String conversationId);

    /** 获取单活所有权后，将旧进程未完成任务标记中断，不执行模型。 */
    void interruptUnfinished();

    /** 是否为跨进程持久归档。 */
    boolean durable();

    /** 历史写入所有权是否仍然有效，供部署就绪检查使用。 */
    default boolean available() {
        return true;
    }

    /** 释放本仓储拥有的数据库所有权连接。 */
    @Override
    default void close() {}

    /** 统一终态判定，恢复和取消使用同一口径。 */
    static boolean active(String status) {
        return "QUEUED".equals(status) || "RUNNING".equals(status);
    }

    /** 限制分页输入，不将任意值拼接到SQL。 */
    static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100000)
            throw new IllegalArgumentException("分页范围无效");
    }

    /** 会话只固定证券与研究日期，各轮可以切换市场/资料范围。 */
    final class Conversation {
        /** 会话标识、标题与证券上下文。 */
        public final String id, title, symbol, asOf, scope;

        /** 服务端创建和最后活动时间。 */
        public final long createdAt, updatedAt;

        /** 构造不可变归档元数据。 */
        public Conversation(
                String id,
                String title,
                String symbol,
                String asOf,
                String scope,
                long createdAt,
                long updatedAt) {
            this.id = id;
            this.title = title;
            this.symbol = symbol;
            this.asOf = asOf;
            this.scope = scope;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /** JSON为编码边界，保留完整报告/引用/角色轨迹，无需runtime依赖具体Agent DTO。 */
    final class Entry {
        /** 任务标识、所属会话、原始问题、执行请求及轮询快照。 */
        public final String id, conversationId, question, requestJson, snapshotJson, status;

        /** 服务端时间戳。 */
        public final long createdAt, updatedAt;

        /** 构造不可变任务快照。 */
        public Entry(
                String id,
                String conversationId,
                String question,
                String requestJson,
                String snapshotJson,
                String status,
                long createdAt,
                long updatedAt) {
            this.id = id;
            this.conversationId = conversationId;
            this.question = question;
            this.requestJson = requestJson;
            this.snapshotJson = snapshotJson;
            this.status = status;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    /** 消息内容与任务状态分别保存，助手失败不会伪装成正常回答。 */
    final class Message {
        /** 会话/任务关联与明确角色、内容、任务状态。 */
        public final String conversationId, taskId, role, content, status;

        /** 消息顺序与创建时间。 */
        public final long id, createdAt;

        /** 构造消息查询结果。 */
        public Message(
                long id,
                String conversationId,
                String taskId,
                String role,
                String content,
                String status,
                long createdAt) {
            this.id = id;
            this.conversationId = conversationId;
            this.taskId = taskId;
            this.role = role;
            this.content = content;
            this.status = status;
            this.createdAt = createdAt;
        }
    }
}
