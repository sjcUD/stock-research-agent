package io.github.sjcud.stockagent.runtime.research;

import static org.junit.jupiter.api.Assertions.*;

import com.mysql.cj.jdbc.MysqlDataSource;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.UUID;

/** MySQL 真写入/读回/清理，仅在显式启用时执行；不依赖固定的 conversation 主键。 */
public class JdbcResearchRunRepositoryIntegrationTest {
    @Test
    public void shouldInsertResearchRun() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MYSQL_INTEGRATION")));
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(env("STOCK_AGENT_MYSQL_URL", "jdbc:mysql://127.0.0.1:3307/stock_agent"));
        ds.setUser(env("STOCK_AGENT_MYSQL_USER", "stock"));
        ds.setPassword(env("STOCK_AGENT_MYSQL_PASSWORD", "stock"));
        long conversationId = 0, runId = 0;
        try (Connection connection = ds.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation (id BIGINT PRIMARY KEY"
                            + " AUTO_INCREMENT, title VARCHAR(200) NOT NULL, created_at TIMESTAMP"
                            + " NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                statement.execute(
                        "CREATE TABLE IF NOT EXISTS research_run (id BIGINT PRIMARY KEY"
                            + " AUTO_INCREMENT, conversation_id BIGINT NOT NULL, status VARCHAR(32)"
                            + " NOT NULL, request_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL"
                            + " DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT"
                            + " CURRENT_TIMESTAMP)");
            }
            try {
                try (PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO conversation(title) VALUES (?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, "evaluation-" + UUID.randomUUID());
                    insert.executeUpdate();
                    try (ResultSet keys = insert.getGeneratedKeys()) {
                        assertTrue(keys.next());
                        conversationId = keys.getLong(1);
                    }
                }
                ResearchRunRecord record =
                        new JdbcResearchRunRepository(ds)
                                .create(conversationId, "QUEUED", "{\"mode\":\"EVALUATION\"}");
                runId = record.getId();
                assertTrue(runId > 0);
                try (PreparedStatement read =
                        connection.prepareStatement(
                                "SELECT conversation_id,status,request_json FROM research_run WHERE"
                                        + " id=?")) {
                    read.setLong(1, runId);
                    try (ResultSet rows = read.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(conversationId, rows.getLong(1));
                        assertEquals("QUEUED", rows.getString(2));
                        assertEquals(record.getRequestJson(), rows.getString(3));
                        assertFalse(rows.next());
                    }
                }
            } finally {
                // 只删除本测试实际拿到的主键，不清空表，不假定测试库是空库。
                try (PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM research_run WHERE id=?")) {
                    delete.setLong(1, runId);
                    delete.executeUpdate();
                }
                try (PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM conversation WHERE id=?")) {
                    delete.setLong(1, conversationId);
                    delete.executeUpdate();
                }
            }
        }
    }

    /** 兼容本地显式配置，不在异常中拼接敏感值。 */
    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.trim().isEmpty() ? fallback : v;
    }
}
