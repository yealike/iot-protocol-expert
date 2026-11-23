package top.yealike.pep.simulator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.codec.frames.OperationFrames;
import top.yealike.pep.simulator.domain.DeviceKey;
import top.yealike.pep.simulator.mapper.SimulatorStateMapper;

/** 显式提供测试数据库连接后执行 PostgreSQL 实测，仅清理本次测试创建的记录。 */
@EnabledIfEnvironmentVariable(named = "IPE_SIMULATOR_TEST_DB_URL", matches = ".+")
class MyBatisSimulatorStateStoreTest {
    static JdbcTemplate jdbc;
    static MyBatisSimulatorStateStore store;
    DeviceKey key = new DeviceKey(-1L, "ocpp16j", "VERIFY-" + UUID.randomUUID());

    @BeforeAll
    static void connect() throws Exception {
        var ds = new DriverManagerDataSource(
                System.getenv("IPE_SIMULATOR_TEST_DB_URL"),
                System.getenv("IPE_SIMULATOR_TEST_DB_USER"),
                System.getenv("IPE_SIMULATOR_TEST_DB_PASSWORD"));
        Flyway.configure()
                .dataSource(ds)
                .defaultSchema("ipe_simulator")
                .schemas("ipe_simulator")
                .locations("classpath:db/simulator-migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(ds);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/simulator/SimulatorStateMapper.xml"));
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SimulatorStateMapper.class);
        store = new MyBatisSimulatorStateStore(mapper, new DataSourceTransactionManager(ds), JsonMapper.shared());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ipe_simulator.charging_record WHERE state_key = ?", key.value());
        jdbc.update("DELETE FROM ipe_simulator.device_state WHERE state_key = ?", key.value());
    }

    Properties snapshot(String value) {
        Properties p = new Properties();
        p.setProperty("value", value);
        return p;
    }

    OperationFrames.Transaction bill(String number) {
        var segment = new OperationFrames.EnergySegment(
                new BigDecimal("1.1"), new BigDecimal("12"), new BigDecimal("12"), new BigDecimal("13.2"));
        return new OperationFrames.Transaction(
                number,
                key.pileCode(),
                "01",
                LocalDateTime.of(2026, 11, 15, 10, 0),
                LocalDateTime.of(2026, 11, 15, 11, 0),
                segment,
                segment,
                segment,
                segment,
                new BigDecimal("1000"),
                new BigDecimal("1048"),
                new BigDecimal("48"),
                new BigDecimal("48"),
                new BigDecimal("52.8"),
                "LSVAA2A40NX000001",
                1,
                LocalDateTime.of(2026, 11, 15, 11, 0),
                0x41,
                "00000000D14B0A54");
    }

    @Test
    void concurrentSavesAreAtomicAndBillsAreIdempotent() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                String value = Integer.toString(i);
                jobs.add(workers.submit(() -> store.save(key, snapshot(value), List.of(bill("OCPP/123")))));
            }
            for (var job : jobs) job.get();
        }
        assertTrue(store.load(key).orElseThrow().containsKey("value"));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ipe_simulator.charging_record WHERE state_key = ?",
                        Integer.class,
                        key.value()));
        assertEquals(
                new BigDecimal("52.8000"),
                jdbc.queryForObject(
                        "SELECT total_amount FROM ipe_simulator.charging_record WHERE state_key = ?",
                        BigDecimal.class,
                        key.value()));
        var payload = jdbc.queryForObject(
                "SELECT bill::text FROM ipe_simulator.charging_record WHERE state_key = ?", String.class, key.value());
        assertEquals(
                key.pileCode(),
                JsonMapper.shared()
                        .readValue(payload, OperationFrames.Transaction.class)
                        .pileCode());
    }

    @Test
    void failedBillInsertRollsBackSnapshotToo() {
        store.save(key, snapshot("before"), List.of());
        assertThrows(DataAccessException.class, () -> store.save(key, snapshot("after"), List.of(bill(null))));
        assertEquals("before", store.load(key).orElseThrow().getProperty("value"));
    }

    @Test
    void archiveRetainsBillsOutsideTheRecentHistoryWindow() {
        store.save(key, snapshot("first"), List.of(bill("oldest")));
        var recent = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> bill("new-" + i))
                .toList();
        store.save(key, snapshot("recent"), recent);
        assertEquals(
                101,
                jdbc.queryForObject(
                        "SELECT count(*) FROM ipe_simulator.charging_record WHERE state_key = ?",
                        Integer.class,
                        key.value()));
    }
}
