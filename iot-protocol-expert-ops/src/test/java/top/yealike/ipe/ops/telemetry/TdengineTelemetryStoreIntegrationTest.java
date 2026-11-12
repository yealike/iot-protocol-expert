package top.yealike.ipe.ops.telemetry;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "IPE_TDENGINE_TEST_URL", matches = ".+")
class TdengineTelemetryStoreIntegrationTest {
    @Test
    void storesAndMergesPartialSamplesAtMicrosecondPrecision() throws Exception {
        String url = System.getenv("IPE_TDENGINE_TEST_URL");
        String user = System.getenv("IPE_TDENGINE_TEST_USER");
        String password = System.getenv("IPE_TDENGINE_TEST_PASSWORD");
        var store = new TdengineTelemetryStore(url, user, password);
        String order = "test-" + UUID.randomUUID();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            assertTrue(store.query(order).isEmpty());
            store.write(new TdengineTelemetryStore.Sample(1, order, Timestamp.from(time), "DEVICE",
                    BigDecimal.valueOf(230), null, null, null, null, null));
            store.write(new TdengineTelemetryStore.Sample(2, order, Timestamp.from(time), "DEVICE",
                    null, BigDecimal.TEN, null, null, null, null));
            var points = store.query(order);
            assertEquals(1, points.size());
            assertEquals(time, points.getFirst().time());
            assertEquals(0, BigDecimal.valueOf(230).compareTo(points.getFirst().voltage()));
            assertEquals(0, BigDecimal.TEN.compareTo(points.getFirst().current()));
        } finally {
            try (var conn = DriverManager.getConnection(url, user, password); var stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS ipe_telemetry." + TdengineTelemetryStore.table(order));
            }
        }
    }
}
