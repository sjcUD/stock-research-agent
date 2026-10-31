package io.github.sjcud.stockagent.runtime.research;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * 基于 JDBC 的 MySQL 运行记录仓储。
 *
 * <p>只依赖标准 JDBC，便于在 Spring Boot、命令行或测试容器中复用；事务
 * 边界由上层服务或 Spring 管理。</p>
 */
public class JdbcResearchRunRepository implements ResearchRunRepository {

    private final DataSource dataSource;

    public JdbcResearchRunRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("DataSource 不能为空");
        }
        this.dataSource = dataSource;
    }

    /** 插入 research_run 并读取自增主键。 */
    @Override
    public ResearchRunRecord create(long conversationId, String status, String requestJson) {
        String sql = "INSERT INTO research_run (conversation_id, status, request_json) VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, new String[] {"id"})) {
            statement.setLong(1, conversationId);
            statement.setString(2, status);
            statement.setString(3, requestJson);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("research_run 未返回自增 ID");
                }
                return new ResearchRunRecord(keys.getLong(1), conversationId, status, requestJson);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("写入 research_run 失败", exception);
        }
    }
}
