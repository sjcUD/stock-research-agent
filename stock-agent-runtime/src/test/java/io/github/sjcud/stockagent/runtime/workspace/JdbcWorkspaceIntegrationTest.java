package io.github.sjcud.stockagent.runtime.workspace;

import static org.junit.jupiter.api.Assertions.*;

import com.mysql.cj.jdbc.MysqlDataSource;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.*;

/** 显式启用的真实MySQL测试，验证事务、单活保护和恢复，不清空用户表。 */
public class JdbcWorkspaceIntegrationTest {
    @Test
    public void shouldPersistRestoreAndRejectConcurrentOwner() throws Exception {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("WORKSPACE_MYSQL_INTEGRATION")));
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(required("STOCK_AGENT_MYSQL_URL"));
        ds.setUser(required("STOCK_AGENT_MYSQL_USER"));
        ds.setPassword(required("STOCK_AGENT_MYSQL_PASSWORD"));
        ds.setConnectTimeout(2000);
        ds.setSocketTimeout(2000);
        String cid = UUID.randomUUID().toString(),
                id = UUID.randomUUID().toString(),
                orphan = UUID.randomUUID().toString();
        try {
            try (JdbcWorkspaceRepository first = new JdbcWorkspaceRepository(ds)) {
                WorkspaceRepository.Conversation c =
                        new WorkspaceRepository.Conversation(
                                cid, "教学测试", "600519", "2026-10-30", "MARKET", 1, 1);
                first.create(
                        c,
                        new WorkspaceRepository.Entry(id, cid, "原始问题", "{}", "{}", "QUEUED", 1, 1));
                assertEquals(2, first.messages(cid).size());
                assertTrue(first.available());
                assertThrows(
                        WorkspaceStorageException.class, () -> new JdbcWorkspaceRepository(ds));
                assertTrue(
                        first.save(
                                new WorkspaceRepository.Entry(
                                        id,
                                        cid,
                                        "原始问题",
                                        "{}",
                                        "{\"result\":\"ok\"}",
                                        "COMPLETED",
                                        1,
                                        2),
                                "ok"));
                assertFalse(
                        first.save(
                                new WorkspaceRepository.Entry(
                                        id, cid, "q", "{}", "{}", "RUNNING", 1, 3),
                                "late"));
                assertEquals("ok", first.messages(cid).get(1).content);
                first.create(
                        c,
                        new WorkspaceRepository.Entry(
                                orphan, cid, "未完成问题", "{}", "{}", "RUNNING", 1, 3));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                first.create(
                                        new WorkspaceRepository.Conversation(
                                                cid,
                                                "wrong",
                                                "000001",
                                                "2026-10-30",
                                                "MARKET",
                                                1,
                                                1),
                                        new WorkspaceRepository.Entry(
                                                UUID.randomUUID().toString(),
                                                cid,
                                                "bad",
                                                "{}",
                                                "{}",
                                                "QUEUED",
                                                1,
                                                4)));
                assertEquals(2, first.entries(cid).size());
            }
            try (JdbcWorkspaceRepository restored = new JdbcWorkspaceRepository(ds)) {
                restored.interruptUnfinished();
                assertEquals("COMPLETED", restored.get(id).status);
                assertEquals("INTERRUPTED", restored.get(orphan).status);
                assertEquals(4, restored.messages(cid).size());
                assertTrue(restored.messages(cid).get(3).content.contains("中断"));
            }
        } finally {
            try (Connection c = ds.getConnection()) {
                for (String table :
                        Arrays.asList(
                                "workspace_message", "workspace_task", "workspace_conversation"))
                    try (PreparedStatement s =
                            c.prepareStatement(
                                    "DELETE FROM "
                                            + table
                                            + " WHERE "
                                            + ("workspace_conversation".equals(table)
                                                    ? "id"
                                                    : "conversation_id")
                                            + "=?")) {
                        s.setString(1, cid);
                        s.executeUpdate();
                    }
            }
        }
    }

    /** 所有权连接在写入途中断开时不能留下新QUEUED任务，避免检查后写入的时间窗口。 */
    @Test
    public void shouldNotCommitAfterOwnershipConnectionIsKilled() throws Exception {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("WORKSPACE_MYSQL_INTEGRATION")));
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(required("STOCK_AGENT_MYSQL_URL"));
        ds.setUser(required("STOCK_AGENT_MYSQL_USER"));
        ds.setPassword(required("STOCK_AGENT_MYSQL_PASSWORD"));
        ds.setConnectTimeout(2000);
        ds.setSocketTimeout(4000);
        String cid = UUID.randomUUID().toString(),
                firstId = UUID.randomUUID().toString(),
                newId = UUID.randomUUID().toString();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (JdbcWorkspaceRepository store = new JdbcWorkspaceRepository(ds);
                Connection blocker = ds.getConnection();
                Connection observer = ds.getConnection()) {
            WorkspaceRepository.Conversation conversation =
                    new WorkspaceRepository.Conversation(
                            cid, "事务故障测试", "600519", "2026-10-30", "MARKET", 1, 1);
            store.create(
                    conversation,
                    new WorkspaceRepository.Entry(firstId, cid, "q", "{}", "{}", "QUEUED", 1, 1));
            blocker.setAutoCommit(false);
            try {
                try (PreparedStatement lock =
                        blocker.prepareStatement(
                                "SELECT id FROM workspace_conversation WHERE id=? FOR UPDATE")) {
                    lock.setString(1, cid);
                    try (ResultSet rows = lock.executeQuery()) {
                        assertTrue(rows.next());
                    }
                }
                java.util.concurrent.Future<?> future =
                        pool.submit(
                                () ->
                                        store.create(
                                                conversation,
                                                new WorkspaceRepository.Entry(
                                                        newId, cid, "new", "{}", "{}", "QUEUED", 1,
                                                        2)));
                boolean waiting = false;
                for (int j = 0; j < 100 && !waiting; j++) {
                    try (PreparedStatement query =
                            observer.prepareStatement(
                                    "SELECT COUNT(*) FROM information_schema.processlist WHERE DB=?"
                                        + " AND INFO LIKE 'INSERT INTO workspace_conversation%'")) {
                        query.setString(1, observer.getCatalog());
                        try (ResultSet rows = query.executeQuery()) {
                            rows.next();
                            waiting = rows.getInt(1) > 0;
                        }
                    }
                    if (!waiting) Thread.sleep(10);
                }
                assertTrue(waiting, "写入应已阻塞在真实会话行锁上");
                long owner;
                try (PreparedStatement query =
                        observer.prepareStatement("SELECT IS_USED_LOCK(?)")) {
                    query.setString(
                            1,
                            "stock-workspace-"
                                    + Integer.toHexString(Objects.hashCode(observer.getCatalog())));
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        owner = rows.getLong(1);
                        assertTrue(owner > 0);
                    }
                }
                try (Statement kill = observer.createStatement()) {
                    kill.execute("KILL CONNECTION " + owner);
                }
                blocker.rollback();
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> future.get(4, java.util.concurrent.TimeUnit.SECONDS));
                try (PreparedStatement query =
                        observer.prepareStatement(
                                "SELECT COUNT(*) FROM workspace_task WHERE id=?")) {
                    query.setString(1, newId);
                    try (ResultSet rows = query.executeQuery()) {
                        rows.next();
                        assertEquals(0, rows.getInt(1));
                    }
                }
            } finally {
                blocker.rollback();
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            try (Connection c = ds.getConnection()) {
                for (String table :
                        Arrays.asList(
                                "workspace_message", "workspace_task", "workspace_conversation"))
                    try (PreparedStatement q =
                            c.prepareStatement(
                                    "DELETE FROM "
                                            + table
                                            + " WHERE "
                                            + ("workspace_conversation".equals(table)
                                                    ? "id"
                                                    : "conversation_id")
                                            + "=?")) {
                        q.setString(1, cid);
                        q.executeUpdate();
                    }
            }
        }
    }

    /** 配置缺失直接失败，不能把显式集成测试静默跳过。 */
    private String required(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("缺少 " + key);
        return v;
    }
}
