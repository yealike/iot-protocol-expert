package top.yealike.pep.ops.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 空库迁移验收：验证 pep_platform/pep_domain/pep_agent 可完整初始化且没有物理外键。
 *
 * <p>通过 PEP_TEST_DB_URL / PEP_TEST_DB_USER / PEP_TEST_DB_PASSWORD 显式启用。
 * 设置 PEP_TEST_RESET_SCHEMAS=true 时先删除本项目三个 schema；只会操作这三个白名单 schema。
 */
@EnabledIfEnvironmentVariable(named = "PEP_TEST_DB_URL", matches = ".+")
class ControlMigrationPostgresTest {

    @Test
    void migratesFreshSchemasWithoutForeignKeys() throws Exception {
        String url = System.getenv("PEP_TEST_DB_URL");
        String user = System.getenv("PEP_TEST_DB_USER");
        String password = System.getenv("PEP_TEST_DB_PASSWORD");
        if ("true".equalsIgnoreCase(System.getenv("PEP_TEST_RESET_SCHEMAS"))) {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                for (String schema : new String[] {"pep_platform", "pep_domain", "pep_agent"}) {
                    statement.execute("drop schema if exists " + schema + " cascade");
                }
            }
        }
        Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("pep_platform")
                .schemas("pep_platform", "pep_domain", "pep_agent")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(count(connection, "select count(*) from pep_platform.tenant"))
                    .isEqualTo(1);
            assertThat(count(connection, "select count(*) from pep_platform.project"))
                    .isEqualTo(1);
            assertThat(count(connection, "select count(*) from pep_platform.role"))
                    .isEqualTo(8);
            assertThat(count(connection, "select count(*) from pep_platform.app_user"))
                    .isNotNegative();
            assertThat(count(connection, "select count(*) from pep_domain.device_instance"))
                    .isNotNegative();
            assertThat(count(connection, "select count(*) from pep_agent.investigation_task"))
                    .isNotNegative();
            assertThat(count(connection, """
                    select count(*) from pep_platform.role_permission
                     where permission_code in ('investigation:create', 'investigation:read', 'investigation:operate')
                    """)).isZero();
            assertThat(
                            count(
                                    connection,
                                    "select count(*) from pg_tables where schemaname = 'pep_domain' and tablename in ('alarm_record', 'alarm_rule')"))
                    .isZero();
            assertThat(count(connection, """
                    select count(*)
                      from pg_constraint c
                      join pg_namespace n on n.oid = c.connamespace
                     where c.contype = 'f'
                       and n.nspname in ('pep_platform', 'pep_domain', 'pep_agent')
                    """)).isZero();
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
