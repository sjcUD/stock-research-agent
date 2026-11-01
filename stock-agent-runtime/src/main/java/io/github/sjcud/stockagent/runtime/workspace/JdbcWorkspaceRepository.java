package io.github.sjcud.stockagent.runtime.workspace;

import java.io.*;
import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

/** MySQL工作台事务仓储；单活连接锁保护启动恢复，SQL条件更新保护终态。 */
public final class JdbcWorkspaceRepository implements WorkspaceRepository {
    /** 读取连接逐次关闭；所有写操作串行使用持有命名锁的连接。 */
    private final DataSource source;

    private final Connection ownership;

    /** 每次应用启动独立的写入者标识。 */
    private final String owner = UUID.randomUUID().toString();

    /** MySQL命名锁按数据库限定，多个学习库可独立启动。 */
    private final String lockName;

    /** 获取唯一写入所有权后初始化独立工作台表，不修改已有业务表。 */
    public JdbcWorkspaceRepository(DataSource source) {
        this.source = Objects.requireNonNull(source);
        Connection acquired = null;
        try {
            acquired = source.getConnection();
            lockName =
                    "stock-workspace-"
                            + Integer.toHexString(Objects.hashCode(acquired.getCatalog()));
            try (PreparedStatement statement = acquired.prepareStatement("SELECT GET_LOCK(?,0)")) {
                statement.setString(1, lockName);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1)
                        throw new IllegalStateException("工作台已有运行实例，不能同时接管历史任务");
                }
            }
            ownership = acquired;
            initialize();
        } catch (Exception ex) {
            if (acquired != null)
                try {
                    acquired.close();
                } catch (SQLException ignored) {
                }
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 幂等初始化第一版独立三表；未来表结构升级需新增迁移，不通过清空数据实现。 */
    private void initialize() throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/workspace/V1__workspace.sql")) {
            if (in == null) throw new IllegalStateException("缺少工作台表定义");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            sql = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (Connection c = source.getConnection();
                Statement s = c.createStatement()) {
            for (String statement : sql.split(";"))
                if (!statement.trim().isEmpty()) s.execute(statement);
        }
    }

    /** 在每次写入前确认命名锁仍由当前连接持有；断线后绝不继续冒充所有者。 */
    private synchronized void checkOwner() throws SQLException {
        try (PreparedStatement s =
                ownership.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            s.setString(1, lockName);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !r.getBoolean(1))
                    throw new SQLException("workspace ownership lost");
            }
        }
    }

    /** 会话上下文行锁串行化同一会话入队，用户与助手消息和任务同事务提交。 */
    @Override
    public void create(Conversation c, Entry e) {
        transaction(
                connection -> {
                    update(
                            connection,
                            "INSERT INTO"
                                + " workspace_conversation(id,title,symbol,as_of,scope,created_at,updated_at)"
                                + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                            c.id,
                            c.title,
                            c.symbol,
                            c.asOf,
                            c.scope,
                            c.createdAt,
                            c.updatedAt);
                    try (PreparedStatement s =
                            connection.prepareStatement(
                                    "SELECT symbol,as_of FROM workspace_conversation WHERE id=? FOR"
                                            + " UPDATE")) {
                        s.setString(1, c.id);
                        try (ResultSet rows = s.executeQuery()) {
                            if (!rows.next()
                                    || !c.symbol.equals(rows.getString(1))
                                    || !c.asOf.equals(rows.getString(2)))
                                throw new IllegalArgumentException("会话上下文不一致");
                        }
                    }
                    try (PreparedStatement s =
                            connection.prepareStatement(
                                    "SELECT COUNT(*) FROM workspace_task WHERE"
                                            + " conversation_id=?")) {
                        s.setString(1, c.id);
                        try (ResultSet rows = s.executeQuery()) {
                            rows.next();
                            if (rows.getInt(1) >= 100)
                                throw new IllegalArgumentException("单会话最多100轮，请新建会话");
                        }
                    }
                    update(
                            connection,
                            "INSERT INTO"
                                + " workspace_task(id,conversation_id,question,request_json,snapshot_json,status,owner_id,created_at,updated_at)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)",
                            e.id,
                            e.conversationId,
                            e.question,
                            e.requestJson,
                            e.snapshotJson,
                            e.status,
                            owner,
                            e.createdAt,
                            e.updatedAt);
                    update(
                            connection,
                            "INSERT INTO"
                                + " workspace_message(conversation_id,task_id,role,content,created_at)"
                                + " VALUES(?,?,?,?,?)",
                            c.id,
                            e.id,
                            "USER",
                            e.question,
                            e.createdAt);
                    update(
                            connection,
                            "INSERT INTO"
                                + " workspace_message(conversation_id,task_id,role,content,created_at)"
                                + " VALUES(?,?,?,?,?)",
                            c.id,
                            e.id,
                            "ASSISTANT",
                            "",
                            e.createdAt);
                    update(
                            connection,
                            "UPDATE workspace_conversation SET updated_at=?,scope=? WHERE id=?",
                            e.updatedAt,
                            c.scope,
                            c.id);
                    return true;
                });
    }

    /** 任务终态和助手消息原子更新，旧进程或迟到结果的条件更新将返回false。 */
    @Override
    public boolean save(Entry e, String answer) {
        return transaction(
                c -> {
                    int count =
                            update(
                                    c,
                                    "UPDATE workspace_task SET"
                                        + " snapshot_json=?,status=?,updated_at=? WHERE id=? AND"
                                        + " conversation_id=? AND owner_id=? AND status IN"
                                        + " ('QUEUED','RUNNING')",
                                    e.snapshotJson,
                                    e.status,
                                    e.updatedAt,
                                    e.id,
                                    e.conversationId,
                                    owner);
                    if (count == 0) return false;
                    update(
                            c,
                            "UPDATE workspace_message SET content=? WHERE task_id=? AND"
                                    + " role='ASSISTANT'",
                            answer,
                            e.id);
                    update(
                            c,
                            "UPDATE workspace_conversation SET updated_at=? WHERE id=?",
                            e.updatedAt,
                            e.conversationId);
                    return true;
                });
    }

    /** 不根据用户请求自动重新执行归档任务。 */
    @Override
    public Entry get(String id) {
        try (Connection c = source.getConnection();
                PreparedStatement s =
                        c.prepareStatement("SELECT * FROM workspace_task WHERE id=?")) {
            s.setString(1, id);
            try (ResultSet rows = s.executeQuery()) {
                if (!rows.next()) throw new NoSuchElementException("任务不存在");
                return entry(rows);
            }
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 使用参数化分页，不返回供应商连接参数。 */
    @Override
    public List<Conversation> conversations(int limit, int offset) {
        WorkspaceRepository.validatePage(limit, offset);
        try (Connection c = source.getConnection();
                PreparedStatement s =
                        c.prepareStatement(
                                "SELECT * FROM workspace_conversation ORDER BY updated_at DESC,id"
                                        + " LIMIT ? OFFSET ?")) {
            s.setInt(1, limit);
            s.setInt(2, offset);
            try (ResultSet r = s.executeQuery()) {
                List<Conversation> values = new ArrayList<Conversation>();
                while (r.next())
                    values.add(
                            new Conversation(
                                    r.getString("id"),
                                    r.getString("title"),
                                    r.getString("symbol"),
                                    r.getString("as_of"),
                                    r.getString("scope"),
                                    r.getLong("created_at"),
                                    r.getLong("updated_at")));
                return values;
            }
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 一段会话最多100轮，按创建时间和ID稳定排序。 */
    @Override
    public List<Entry> entries(String cid) {
        try (Connection c = source.getConnection();
                PreparedStatement s =
                        c.prepareStatement(
                                "SELECT * FROM workspace_task WHERE conversation_id=? ORDER BY"
                                        + " created_at,id")) {
            s.setString(1, cid);
            try (ResultSet r = s.executeQuery()) {
                List<Entry> values = new ArrayList<Entry>();
                while (r.next()) values.add(entry(r));
                return values;
            }
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 独立消息接口与任务状态连接，不重复保存互相矛盾的消息状态。 */
    @Override
    public List<Message> messages(String cid) {
        try (Connection c = source.getConnection();
                PreparedStatement s =
                        c.prepareStatement(
                                "SELECT m.*,t.status FROM workspace_message m JOIN workspace_task t"
                                        + " ON t.id=m.task_id WHERE m.conversation_id=? ORDER BY"
                                        + " m.id")) {
            s.setString(1, cid);
            try (ResultSet r = s.executeQuery()) {
                List<Message> values = new ArrayList<Message>();
                while (r.next())
                    values.add(
                            new Message(
                                    r.getLong("id"),
                                    cid,
                                    r.getString("task_id"),
                                    r.getString("role"),
                                    r.getString("content"),
                                    r.getString("status"),
                                    r.getLong("created_at")));
                return values;
            }
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 只有获得数据库唯一所有权的进程才执行恢复，终态历史保持不变。 */
    @Override
    public void interruptUnfinished() {
        transaction(
                c -> {
                    update(
                            c,
                            "UPDATE workspace_message m JOIN workspace_task t ON t.id=m.task_id SET"
                                    + " m.content=? WHERE m.role='ASSISTANT' AND t.status IN"
                                    + " ('QUEUED','RUNNING')",
                            "服务已重启，本次研究中断；请确认后重新发起。");
                    update(
                            c,
                            "UPDATE workspace_task SET status='INTERRUPTED',updated_at=? WHERE"
                                    + " status IN ('QUEUED','RUNNING')",
                            System.currentTimeMillis());
                    return true;
                });
    }

    /** 明确跨进程持久化能力。 */
    @Override
    public boolean durable() {
        return true;
    }

    /** 健康轮询也保持命名锁连接活跃；连接丢失需重启实例重新恢复，不能静默接管。 */
    @Override
    public boolean available() {
        try {
            checkOwner();
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    /** 关闭所有权连接自动释放命名锁，不清理任何历史数据。 */
    @Override
    public synchronized void close() {
        try {
            ownership.close();
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 写事务与所有权使用同一连接，连接中途失效会同时失去锁并回滚，消除先检查再另连写入的窗口。 */
    private synchronized boolean transaction(Work work) {
        try {
            checkOwner();
            ownership.setAutoCommit(false);
            try {
                boolean result = work.run(ownership);
                ownership.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    ownership.rollback();
                } catch (SQLException rollbackFailure) {
                    // 回滚结果不确定时关闭连接，不用setAutoCommit(true)意外提交部分结果。
                    try {
                        ownership.close();
                    } catch (SQLException ignored) {
                    }
                }
                throw ex;
            } finally {
                if (!ownership.isClosed()) ownership.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new WorkspaceStorageException(ex);
        }
    }

    /** 所有值使用绑定参数，不将问题、内容或ID拼接为SQL。 */
    private int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            return s.executeUpdate();
        }
    }

    /** JDBC行转换集中处理，避免查询之间字段口径不一致。 */
    private Entry entry(ResultSet r) throws SQLException {
        return new Entry(
                r.getString("id"),
                r.getString("conversation_id"),
                r.getString("question"),
                r.getString("request_json"),
                r.getString("snapshot_json"),
                r.getString("status"),
                r.getLong("created_at"),
                r.getLong("updated_at"));
    }

    /** 事务回调仅在仓储内部使用，不暴露数据库连接给模型或HTTP层。 */
    private interface Work {
        boolean run(Connection connection) throws SQLException;
    }
}
