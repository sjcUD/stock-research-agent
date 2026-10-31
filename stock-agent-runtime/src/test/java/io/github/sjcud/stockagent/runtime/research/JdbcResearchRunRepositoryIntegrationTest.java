package io.github.sjcud.stockagent.runtime.research;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** MySQL 真实写入测试，仅在 MYSQL_INTEGRATION=true 时执行。 */
public class JdbcResearchRunRepositoryIntegrationTest {

    @Test
    public void shouldInsertResearchRun() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("MYSQL_INTEGRATION")));
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setServerName("127.0.0.1");
        dataSource.setPortNumber(Integer.parseInt(
                System.getProperty("stock.mysql.port", "3307")));
        dataSource.setDatabaseName("stock_agent");
        dataSource.setUser("stock");
        dataSource.setPassword("stock");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS conversation ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT, title VARCHAR(200) NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE IF NOT EXISTS research_run ("
                    + "id BIGINT PRIMARY KEY AUTO_INCREMENT, conversation_id BIGINT NOT NULL,"
                    + "status VARCHAR(32) NOT NULL, request_json TEXT NOT NULL,"
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO conversation (title) VALUES ('integration')");
            long conversationId = 1L;
            ResearchRunRecord record = new JdbcResearchRunRepository(dataSource)
                    .create(conversationId, "QUEUED", "{\"symbol\":\"600519\"}");
            assertTrue(record.getId().longValue() > 0L);
        }
    }
}
