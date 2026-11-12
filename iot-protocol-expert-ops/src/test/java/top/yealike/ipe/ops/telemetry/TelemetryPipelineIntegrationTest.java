package top.yealike.ipe.ops.telemetry;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.ipe.ops.domain.ChargeOrder;
import top.yealike.ipe.ops.service.OrderService;

@EnabledIfEnvironmentVariable(named = "IPE_PIPELINE_TEST_DB_URL", matches = ".+")
class TelemetryPipelineIntegrationTest {
    @Test
    void migrationAndOutboxRetainFailedWritesAndPublishSuccessfulWrites() throws Exception {
        String tdUrl = System.getenv("IPE_TDENGINE_TEST_URL");
        String tdUser = System.getenv("IPE_TDENGINE_TEST_USER");
        String tdPassword = System.getenv("IPE_TDENGINE_TEST_PASSWORD");
        var ds = new DriverManagerDataSource(System.getenv("IPE_PIPELINE_TEST_DB_URL"),
                System.getenv("IPE_PIPELINE_TEST_DB_USER"), System.getenv("IPE_PIPELINE_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("ipe_ops").defaultSchema("ipe_ops")
                .locations("classpath:db/migration").placeholderReplacement(false).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var store = new TdengineTelemetryStore(tdUrl, tdUser, tdPassword);
        var service = new OrderTelemetryService(jdbc, mock(OrderService.class), store);
        var order = new ChargeOrder(); order.orderNo = "t-" + UUID.randomUUID();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            tx.executeWithoutResult(status -> service.record(order, time, "DEVICE", BigDecimal.TEN,
                    null, null, null, null, null));
            assertThrows(IllegalStateException.class, () -> service.query(order.orderNo));
            var failing = mock(TdengineTelemetryStore.class);
            doThrow(new java.sql.SQLException("simulated failure")).when(failing).write(any());
            var bad = new TelemetryOutboxPublisher(jdbc, failing);
            assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
                try { bad.publish(); } catch (Exception ex) { throw new IllegalStateException(ex); }
            }));
            assertEquals(1, jdbc.queryForObject("select count(*) from ipe_ops.order_telemetry_outbox", Integer.class));
            var publisher = new TelemetryOutboxPublisher(jdbc, store);
            tx.executeWithoutResult(status -> {
                try { publisher.publish(); } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
            assertEquals(0, jdbc.queryForObject("select count(*) from ipe_ops.order_telemetry_outbox", Integer.class));
            assertEquals(0, BigDecimal.TEN.compareTo(service.query(order.orderNo).points().getFirst().voltage()));
        } finally {
            try (var conn = DriverManager.getConnection(tdUrl, tdUser, tdPassword); var stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS ipe_telemetry." + TdengineTelemetryStore.table(order.orderNo));
            }
        }
    }
}

