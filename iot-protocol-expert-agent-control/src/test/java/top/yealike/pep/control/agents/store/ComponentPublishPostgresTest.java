package top.yealike.pep.control.agents.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.yealike.pep.control.agents.mapper.McpPlatformMapper;
import top.yealike.pep.control.agents.mapper.SkillPlatformMapper;

/**
 * 隔离 PostgreSQL 验收发布 SQL 的类型与代次推进。
 *
 * <p>通过 IPE_REVIEW_PG_URL / IPE_REVIEW_PG_USER / IPE_REVIEW_PG_PASSWORD 显式启用；测试只创建临时 schema，
 * 结束后删除，不接触共享 schema。
 */
@EnabledIfEnvironmentVariable(named = "IPE_REVIEW_PG_URL", matches = ".+")
class ComponentPublishPostgresTest {

    @Test
    void mcpAndSkillAdvanceDefaultPointerAndRejectStaleGeneration() throws Exception {
        String schema = "ipe_review_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                System.getenv("IPE_REVIEW_PG_URL"),
                System.getenv("IPE_REVIEW_PG_USER"),
                System.getenv("IPE_REVIEW_PG_PASSWORD"))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
            }
            try {
                connection.setSchema(schema);
                createTables(connection);
                var dataSource = new SingleConnectionDataSource(connection, true);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                var factory = new SqlSessionFactoryBean();
                factory.setDataSource(dataSource);
                var configuration = new org.apache.ibatis.session.Configuration();
                configuration.setMapUnderscoreToCamelCase(true);
                factory.setConfiguration(configuration);
                factory.setMapperLocations(
                        new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
                var session = new SqlSessionTemplate(factory.getObject());

                McpStore mcps = new McpStore(session.getMapper(McpPlatformMapper.class));
                assertPublished(
                        jdbc, "ai_mcp", releaseId -> mcps.setReleaseState(releaseId, "ACTIVE"), mcps::publishComponent);
                SkillStore skills = new SkillStore(session.getMapper(SkillPlatformMapper.class));
                assertPublished(
                        jdbc,
                        "ai_skill",
                        releaseId -> skills.setReleaseState(releaseId, "ACTIVE"),
                        skills::publishComponent);
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

    private static void assertPublished(
            JdbcTemplate jdbc, String prefix, ToIntFunction<UUID> setActive, PublishTarget publish) {
        UUID componentId = UUID.randomUUID();
        String releaseOwner = "ai_skill".equals(prefix) ? "skill_id" : "component_id";
        jdbc.update(
                "insert into " + prefix
                        + "_component (id, published_version, publication_generation, revision) values (?, null, 0, 0)",
                componentId);
        UUID releaseId = UUID.randomUUID();
        jdbc.update(
                "insert into " + prefix + "_release (id, " + releaseOwner
                        + ", version, generation, state) values (?, ?, '1.2.3', 7, 'STAGED')",
                releaseId,
                componentId);

        assertThat(setActive.applyAsInt(releaseId)).isEqualTo(1);
        assertThat(publish.publish(componentId, "1.2.3", 7L)).isTrue();
        assertThat(jdbc.queryForObject(
                        "select published_version from " + prefix + "_component where id = ?",
                        String.class,
                        componentId))
                .isEqualTo("1.2.3");
        assertThat(jdbc.queryForObject(
                        "select publication_generation from " + prefix + "_component where id = ?",
                        Long.class,
                        componentId))
                .isEqualTo(7L);

        assertThat(publish.publish(componentId, "1.2.3", 7L)).isTrue();
        UUID staleRelease = UUID.randomUUID();
        jdbc.update(
                "insert into " + prefix + "_release (id, " + releaseOwner
                        + ", version, generation, state) values (?, ?, '2.0.0', 6, 'ACTIVE')",
                staleRelease,
                componentId);
        assertThat(publish.publish(componentId, "2.0.0", 6L)).isFalse();
        assertThat(jdbc.queryForObject(
                        "select published_version from " + prefix + "_component where id = ?",
                        String.class,
                        componentId))
                .isEqualTo("1.2.3");
        assertThat(jdbc.queryForObject(
                        "select publication_generation from " + prefix + "_component where id = ?",
                        Long.class,
                        componentId))
                .isEqualTo(7L);
    }

    private static void createTables(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String prefix : new String[] {"ai_mcp", "ai_skill"}) {
                String releaseOwner = "ai_skill".equals(prefix) ? "skill_id" : "component_id";
                statement.execute(
                        "create table " + prefix
                                + "_component (id uuid primary key, published_version varchar(64), publication_generation bigint not null default 0, revision bigint not null default 0, updated_at timestamptz not null default now())");
                statement.execute(
                        "create table " + prefix
                                + "_release (id uuid primary key, " + releaseOwner
                                + " uuid not null, version varchar(64) not null, generation bigint not null, state varchar(16) not null)");
            }
        }
    }

    @FunctionalInterface
    private interface PublishTarget {
        boolean publish(UUID componentId, String version, long generation);
    }
}
