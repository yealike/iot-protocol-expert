package top.yealike.pep.control.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.control.charging.domain.ChargeOrder;
import top.yealike.pep.control.telemetry.mapper.OrderTelemetryMapper;
import top.yealike.pep.control.charging.service.OrderService;

@EnabledIfEnvironmentVariable(named = "IPE_PIPELINE_TEST_DB_URL", matches = ".+")
class TelemetryPipelineIntegrationTest {
    @Test
    void migrationAndOutboxRetainFailedWritesAndPublishSuccessfulWrites() throws Exception {
        String tdUrl = System.getenv("IPE_TDENGINE_TEST_URL");
        String tdUser = System.getenv("IPE_TDENGINE_TEST_USER");
        String tdPassword = System.getenv("IPE_TDENGINE_TEST_PASSWORD");
        var ds = new DriverManagerDataSource(
                System.getenv("IPE_PIPELINE_TEST_DB_URL"),
                System.getenv("IPE_PIPELINE_TEST_DB_USER"),
                System.getenv("IPE_PIPELINE_TEST_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .schemas("ipe_ops")
                .defaultSchema("ipe_ops")
                .locations("classpath:db/migration")
                .placeholderReplacement(false)
                .load()
                .migrate();
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var store = new TdengineTelemetryStore(tdUrl, tdUser, tdPassword);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/ops/OrderTelemetryMapper.xml"));
        var telemetryMapper = new SqlSessionTemplate(factory.getObject()).getMapper(OrderTelemetryMapper.class);
        var service = new OrderTelemetryService(telemetryMapper, mock(OrderService.class), store);
        var order = new ChargeOrder();
        order.orderNo = "t-" + UUID.randomUUID();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            tx.executeWithoutResult(
                    status -> service.record(order, time, "DEVICE", BigDecimal.TEN, null, null, null, null, null));
            assertThrows(IllegalStateException.class, () -> service.query(order.orderNo));
            var failing = mock(TdengineTelemetryStore.class);
            doThrow(new IllegalStateException("simulated failure"))
                    .when(failing)
                    .write(any());
            var bad = new TelemetryOutboxPublisher(telemetryMapper, failing);
            assertThrows(
                    RuntimeException.class,
                    () -> tx.executeWithoutResult(status -> {
                        try {
                            bad.publish();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }));
            assertEquals(1, jdbc.queryForObject("select count(*) from pep_domain.order_telemetry_outbox", Integer.class));
            var publisher = new TelemetryOutboxPublisher(telemetryMapper, store);
            tx.executeWithoutResult(status -> {
                try {
                    publisher.publish();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
            assertEquals(0, jdbc.queryForObject("select count(*) from pep_domain.order_telemetry_outbox", Integer.class));
            assertEquals(
                    0,
                    BigDecimal.TEN.compareTo(
                            service.query(order.orderNo).points().getFirst().voltage()));
        } finally {
            try (var conn = DriverManager.getConnection(tdUrl, tdUser, tdPassword);
                    var stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS ipe_telemetry." + TdengineTelemetryStore.table(order.orderNo));
            }
        }
    }
}
