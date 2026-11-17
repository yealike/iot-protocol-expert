package top.yealike.pep.modelgateway.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 空库迁移验收：pep_metering 建表、种子策略与零物理外键。 */
@EnabledIfEnvironmentVariable(named = "PEP_TEST_DB_URL", matches = ".+")
class MeteringMigrationPostgresTest {

    @Test
    void migratesMeteringSchema() throws Exception {
        String url = System.getenv("PEP_TEST_DB_URL");
        String user = System.getenv("PEP_TEST_DB_USER");
        String password = System.getenv("PEP_TEST_DB_PASSWORD");
        if ("true".equalsIgnoreCase(System.getenv("PEP_TEST_RESET_SCHEMAS"))) {
            try (Connection connection = DriverManager.getConnection(url, user, password);
                    Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists pep_metering cascade");
            }
        }
        Flyway.configure()
                .dataSource(url, user, password)
                .defaultSchema("pep_metering")
                .schemas("pep_metering")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertThat(count(connection, "select count(*) from pep_metering.model_provider"))
                    .isNotNegative();
            assertThat(count(connection, "select count(*) from pep_metering.quota_account"))
                    .isNotNegative();
            assertThat(count(connection, "select count(*) from pep_metering.quota_policy_version"))
                    .isEqualTo(1);
            assertThat(count(connection, """
                    select count(*)
                      from pg_constraint c
                      join pg_namespace n on n.oid = c.connamespace
                     where c.contype = 'f' and n.nspname = 'pep_metering'
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
