package io.github.sjcud.stockagent.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.jupiter.api.Test;

/** 验证 MySQL 初始迁移包含最小研究状态表。 */
public class MigrationFileTest {

    @Test
    public void shouldDefineResearchRunAndDocumentRevisionTables() {
        InputStream stream = getClass().getResourceAsStream("/db/migration/V1__baseline.sql");
        assertTrue(stream != null, "迁移文件必须存在");
        String sql = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A").next();
        assertTrue(sql.contains("CREATE TABLE research_run"));
        assertTrue(sql.contains("CREATE TABLE document_revision"));
    }
}
