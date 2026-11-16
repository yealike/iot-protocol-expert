package top.yealike.ipe.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import top.yealike.ipe.ai.mapper.RunMapper;

/**
 * 隔离 PostgreSQL 验收 Control 权威终结过期 Run。
 *
 * <p>通过 IPE_REVIEW_PG_URL / IPE_REVIEW_PG_USER / IPE_REVIEW_PG_PASSWORD 显式启用；测试只创建临时 schema，
 * 结束后删除，不接触共享 schema。
 */
@EnabledIfEnvironmentVariable(named = "IPE_REVIEW_PG_URL", matches = ".+")
class RunExpiryPostgresTest {

    @Test
    void terminateExpiredOnlyEndsRowsThatAreStillExpiredOrOverdue() throws Exception {
        String schema = "ipe_review_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = System.getenv("IPE_REVIEW_PG_URL");
        String schemaUrl = databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection connection = DriverManager.getConnection(
                databaseUrl, System.getenv("IPE_REVIEW_PG_USER"), System.getenv("IPE_REVIEW_PG_PASSWORD"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
            }
            connection.setSchema(schema);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            create table ai_run (
                                id uuid primary key,
                                status varchar(32) not null,
                                execution_token bigint not null default 0,
                                runtime_instance_id varchar(128),
                                lease_owner varchar(128),
                                lease_until timestamptz,
                                deadline_at timestamptz,
                                error_code varchar(64),
                                error_message text,
                                finished_at timestamptz,
                                updated_at timestamptz not null default now()
                            )
                            """);
                }
                RunRepository runs = repository(schemaUrl);

                UUID expiredLease = UUID.randomUUID();
                insert(
                        connection,
                        expiredLease,
                        "RUNNING",
                        7L,
                        "runtime-1",
                        "now() - interval '5 seconds'",
                        "now() + interval '50 seconds'");
                assertThat(runs.terminateExpired(expiredLease, 7L, "runtime-1", "EXECUTION_LOST", "执行租约已过期"))
                        .isEqualTo("FAILED");
                assertRow(connection, expiredLease, "FAILED", "EXECUTION_LOST", "执行租约已过期", true);

                UUID expiredDeadline = UUID.randomUUID();
                insert(
                        connection,
                        expiredDeadline,
                        "RUNNING",
                        8L,
                        "runtime-1",
                        "now() + interval '50 seconds'",
                        "now() - interval '5 seconds'");
                assertThat(runs.terminateExpired(expiredDeadline, 8L, "runtime-1", "RUN_TIMED_OUT", "Run 已超过执行截止时间"))
                        .isEqualTo("FAILED");
                assertRow(connection, expiredDeadline, "FAILED", "RUN_TIMED_OUT", "Run 已超过执行截止时间", true);

                UUID renewed = UUID.randomUUID();
                insert(
                        connection,
                        renewed,
                        "RUNNING",
                        9L,
                        "runtime-1",
                        "now() + interval '50 seconds'",
                        "now() + interval '50 seconds'");
                assertThat(runs.terminateExpired(renewed, 9L, "runtime-1", "EXECUTION_LOST", "不应终结"))
                        .isNull();
                assertRow(connection, renewed, "RUNNING", null, null, false);

                UUID cancelling = UUID.randomUUID();
                insert(
                        connection,
                        cancelling,
                        "CANCEL_REQUESTED",
                        10L,
                        "runtime-1",
                        "now() - interval '5 seconds'",
                        "now() + interval '50 seconds'");
                assertThat(runs.terminateExpired(cancelling, 10L, "runtime-1", "EXECUTION_LOST", "取消确认超时"))
                        .isEqualTo("CANCELLED");
                assertRow(connection, cancelling, "CANCELLED", null, "Runtime 取消确认超时，由 Control 终结", true);

                UUID staleToken = UUID.randomUUID();
                insert(
                        connection,
                        staleToken,
                        "RUNNING",
                        11L,
                        "runtime-1",
                        "now() - interval '5 seconds'",
                        "now() + interval '50 seconds'");
                assertThat(runs.terminateExpired(staleToken, 99L, "runtime-1", "EXECUTION_LOST", "不应终结"))
                        .isNull();
                assertRow(connection, staleToken, "RUNNING", null, null, false);

                UUID otherRuntime = UUID.randomUUID();
                insert(
                        connection,
                        otherRuntime,
                        "RUNNING",
                        12L,
                        "runtime-2",
                        "now() - interval '5 seconds'",
                        "now() + interval '50 seconds'");
                assertThat(runs.terminateExpired(otherRuntime, 12L, "runtime-1", "EXECUTION_LOST", "不应终结"))
                        .isNull();
                assertRow(connection, otherRuntime, "RUNNING", null, null, false);
            } finally {
                try {
                    connection.setSchema("public");
                } catch (RuntimeException ignored) {
                    // schema 清理优先，search_path 恢复失败不影响 DROP SCHEMA CASCADE。
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("drop schema if exists " + schema + " cascade");
                }
            }
        }
    }

    private static RunRepository repository(String url) throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.postgresql.Driver",
                url,
                System.getenv("IPE_REVIEW_PG_USER"),
                System.getenv("IPE_REVIEW_PG_PASSWORD"));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/ai/RunMapper.xml"));
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        return new RunRepository(new SqlSessionTemplate(sqlSessionFactory).getMapper(RunMapper.class));
    }

    private static void insert(
            Connection connection,
            UUID id,
            String status,
            long token,
            String instanceId,
            String leaseExpression,
            String deadlineExpression)
            throws Exception {
        String sql = "insert into ai_run (id, status, execution_token, runtime_instance_id, lease_owner, "
                + "lease_until, deadline_at, updated_at) values (?, ?, ?, ?, ?, "
                + leaseExpression
                + ", "
                + deadlineExpression
                + ", now())";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, status);
            statement.setLong(3, token);
            statement.setString(4, instanceId);
            statement.setString(5, instanceId);
            statement.executeUpdate();
        }
    }

    private static void assertRow(
            Connection connection, UUID id, String status, String errorCode, String errorMessage, boolean finished)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select status, error_code, error_message, lease_owner, lease_until, finished_at from ai_run where id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo(status);
                assertThat(rows.getString("error_code")).isEqualTo(errorCode);
                assertThat(rows.getString("error_message")).isEqualTo(errorMessage);
                if (finished) {
                    assertThat(rows.getString("lease_owner")).isNull();
                    assertThat(rows.getTimestamp("lease_until")).isNull();
                    assertThat(rows.getTimestamp("finished_at")).isNotNull();
                } else {
                    assertThat(rows.getString("lease_owner")).isNotNull();
                    assertThat(rows.getTimestamp("lease_until")).isNotNull();
                    assertThat(rows.getTimestamp("finished_at")).isNull();
                }
            }
        }
    }
}
