package top.yealike.pep.control.investigations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import top.yealike.pep.control.access.AccessAdminService;
import top.yealike.pep.control.access.AccessService;
import top.yealike.pep.control.access.UserIdentity;
import top.yealike.pep.control.access.mapper.AccessAdminMapper;
import top.yealike.pep.control.access.mapper.AccessMapper;
import top.yealike.pep.control.investigations.mapper.InvestigationMapper;

/**
 * 调查任务真实 PostgreSQL 验收：项目授权、状态跃迁、证据、假设、实验、结论与审批。
 *
 * <p>通过 PEP_INVESTIGATION_TEST=true 显式启用；只创建并删除本次测试的数据行。
 */
@EnabledIfEnvironmentVariable(named = "PEP_INVESTIGATION_TEST", matches = "true")
class InvestigationPostgresTest {

    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static SqlSessionFactory factory;
    private static long adminUserId;

    private SqlSession session;

    @BeforeAll
    static void load() throws Exception {
        String url = System.getenv("PEP_TEST_DB_URL");
        UnpooledDataSource dataSource = new UnpooledDataSource(
                "org.postgresql.Driver", url, System.getenv("PEP_TEST_DB_USER"), System.getenv("PEP_TEST_DB_PASSWORD"));
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        bean.setConfiguration(configuration);
        var resolver = new PathMatchingResourcePatternResolver();
        bean.setMapperLocations(new Resource[] {
            resolver.getResource("classpath:mapper/ops/AccessMapper.xml"),
            resolver.getResource("classpath:mapper/ops/AccessAdminMapper.xml"),
            resolver.getResource("classpath:mapper/ops/InvestigationMapper.xml")
        });
        factory = bean.getObject();
        try (Connection connection = DriverManager.getConnection(
                        url, System.getenv("PEP_TEST_DB_USER"), System.getenv("PEP_TEST_DB_PASSWORD"));
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select id from pep_platform.app_user where state = 'ACTIVE' order by id limit 1")) {
            rows.next();
            adminUserId = rows.getLong(1);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        if (session != null) {
            session.close();
        }
    }

    @Test
    void fullInvestigationFlowKeepsEvidenceAndVersionedConclusion() throws Exception {
        session = factory.openSession();
        UserIdentity actor = new UserIdentity(
                String.valueOf(adminUserId),
                "admin",
                List.of("PLATFORM_ADMIN"),
                List.of("investigation:create", "investigation:read", "investigation:operate"),
                1L);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));

        AccessMapper accessMapper = session.getMapper(AccessMapper.class);
        AccessAdminMapper adminMapper = session.getMapper(AccessAdminMapper.class);
        AccessService access = new AccessService(accessMapper);
        InvestigationMapper mapper = session.getMapper(InvestigationMapper.class);
        InvestigationService service = new InvestigationService(mapper, access);
        AccessAdminService admin = new AccessAdminService(adminMapper, access);

        var task = service.create(PROJECT_ID, "启动失败定位", "定位充电桩启动失败原因并复现", Map.of("deviceId", "demo"));
        assertThat(task.status()).isEqualTo("OPEN");
        assertThat(task.version()).isZero();
        assertThat(admin.tenants()).isNotEmpty();
        assertThat(admin.projects(admin.tenants().getFirst().id())).isNotEmpty();
        assertThat(admin.roles()).hasSizeGreaterThanOrEqualTo(8);

        task = service.transition(task.id(), 0, "INVESTIGATING", "开始排查");
        assertThat(task.status()).isEqualTo("INVESTIGATING");
        assertThat(task.version()).isEqualTo(1);

        OffsetDateTime now = OffsetDateTime.now();
        var evidence = service.addEvidence(
                task.id(),
                "DEVICE",
                "1",
                "v1",
                "设备上报启动失败 0x23",
                "td://frame/1",
                Map.of("deviceId", "1"),
                now,
                now.plusDays(180),
                Map.of("source", "test"));
        assertThat(evidence.contentHash()).hasSize(64);

        var hypothesis = service.addHypothesis(
                task.id(), "BMS 握手超时", List.of(evidence.id().toString()));
        assertThat(hypothesis.status()).isEqualTo("OPEN");
        assertThat(service.updateHypothesis(task.id(), hypothesis.id(), "SUPPORTED")
                        .status())
                .isEqualTo("SUPPORTED");

        var experiment = service.addExperiment(task.id(), "scenario-2026.11.1", Map.of("assert", "start.failed"));
        assertThat(experiment.status()).isEqualTo("REQUESTED");
        var completed =
                service.updateExperiment(task.id(), experiment.id(), "COMPLETED", "复现成功", "sim://run/1", "sim-run-1");
        assertThat(completed.status()).isEqualTo("COMPLETED");

        var conclusion = service.addConclusion(task.id(), "BMS 握手超时导致启动失败", List.of(evidence.id()), "VERIFIED");
        assertThat(conclusion.version()).isEqualTo(1);
        assertThat(service.addConclusion(task.id(), "补充结论", List.of(evidence.id()), "UNVERIFIED")
                        .version())
                .isEqualTo(2);

        var approval =
                service.requestApproval(task.id(), "device:command:start", Map.of("deviceId", "1"), now.plusHours(1));
        assertThat(approval.state()).isEqualTo("PENDING");
        assertThat(service.useApproval(task.id(), approval.id()).state()).isEqualTo("USED");
        assertThat(service.events(task.id(), 0, 100)).hasSizeGreaterThanOrEqualTo(7);

        session.commit();
        clean(task.id());
    }

    private void clean(UUID taskId) throws Exception {
        String url = System.getenv("PEP_TEST_DB_URL");
        try (Connection connection = DriverManager.getConnection(
                url, System.getenv("PEP_TEST_DB_USER"), System.getenv("PEP_TEST_DB_PASSWORD"))) {
            try (var statement = connection.prepareStatement(
                    "delete from pep_agent.evidence_item where id in (select evidence_id from pep_agent.task_evidence where task_id = ?)")) {
                statement.setObject(1, taskId);
                statement.executeUpdate();
            }
            for (String table : List.of(
                    "action_approval",
                    "task_conclusion",
                    "task_experiment",
                    "diagnostic_hypothesis",
                    "task_evidence",
                    "task_event")) {
                try (var statement =
                        connection.prepareStatement("delete from pep_agent." + table + " where task_id = ?")) {
                    statement.setObject(1, taskId);
                    statement.executeUpdate();
                }
            }
            try (var statement = connection.prepareStatement("delete from pep_agent.investigation_task where id = ?")) {
                statement.setObject(1, taskId);
                statement.executeUpdate();
            }
        }
    }
}
