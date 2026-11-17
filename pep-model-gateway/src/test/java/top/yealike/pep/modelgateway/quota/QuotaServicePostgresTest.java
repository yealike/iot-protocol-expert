package top.yealike.pep.modelgateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.model.contract.ModelUsage;
import top.yealike.pep.modelgateway.config.GatewayProperties;
import top.yealike.pep.modelgateway.support.ApiException;

/**
 * 配额账本真实 PostgreSQL 验收。
 *
 * <p>通过 PEP_METERING_TEST=true 显式启用；测试会清空 pep_metering 下的账本表，
 * 只允许在本项目专用 schema 的测试库执行。
 */
@EnabledIfEnvironmentVariable(named = "PEP_METERING_TEST", matches = "true")
class QuotaServicePostgresTest {

    private JdbcTemplate jdbc;
    private QuotaService quota;
    private String subject;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                System.getenv("PEP_TEST_DB_URL"),
                System.getenv("PEP_TEST_DB_USER"),
                System.getenv("PEP_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                truncate pep_metering.quota_ledger_entry, pep_metering.reservation_charge,
                         pep_metering.quota_reservation, pep_metering.model_usage,
                         pep_metering.model_call, pep_metering.quota_bucket,
                         pep_metering.quota_account, pep_metering.quota_policy_version,
                         pep_metering.usage_adjustment, pep_metering.quota_change_request
                """);
        var properties = new GatewayProperties();
        properties.setDispatchDeadlineSeconds(30);
        quota = new QuotaService(
                new QuotaRepository(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                properties);
        subject = "u-" + UUID.randomUUID();
    }

    @Test
    void windowStartsOnFirstReservationAndSettlementUsesNormalizedUsage() {
        grant("ON_FIRST_USE_WINDOW", 100, 18_000L, "win-" + subject);
        assertThat(quota.snapshot("USER", subject).get("policies"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("activationState", "NOT_ACTIVATED");

        var reservation = quota.reserve(request(10));
        assertThat(reservation.replayed()).isFalse();
        assertThat(reservation.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.activationState()).isEqualTo("PROVISIONAL");
            assertThat(bucket.endAt().toInstant().getEpochSecond()
                            - bucket.startAt().toInstant().getEpochSecond())
                    .isEqualTo(18_000L);
        });
        quota.markDispatched(reservation.reservationId());
        quota.settle(
                reservation.reservationId(), usage(7), "provider-1", "{\"prompt_tokens\":4,\"completion_tokens\":3}");

        var snapshot = quota.snapshot("USER", subject);
        @SuppressWarnings("unchecked")
        var policy = ((List<java.util.Map<String, Object>>) snapshot.get("policies")).getFirst();
        assertThat(policy).containsEntry("activationState", "ACTIVE").containsEntry("usedUnits", 7L);
        assertThat(policy).containsEntry("remainingUnits", 93L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from pep_metering.model_usage where quality = 'PROVIDER'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void concurrentReservationsCannotExceedLimit() throws Exception {
        grant("TOTAL", 100, null, "total-" + subject);
        List<Callable<Boolean>> tasks = List.of(
                () -> {
                    quota.reserve(request(70));
                    return true;
                },
                () -> {
                    quota.reserve(request(40));
                    return true;
                });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(tasks);
            int successes = 0;
            for (Future<Boolean> future : futures) {
                try {
                    if (future.get(20, TimeUnit.SECONDS)) {
                        successes++;
                    }
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ApiException.class);
                    assertThat(((ApiException) exception.getCause()).status()).isEqualTo(429);
                }
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_reservation", Long.class))
                .isEqualTo(1L);
        long held =
                jdbc.queryForObject("select coalesce(sum(held_units), 0) from pep_metering.quota_bucket", Long.class);
        assertThat(held).isIn(70L, 40L);
    }

    @Test
    void multiplePoliciesFailAtomicallyWithoutLeakingHold() {
        grant("TOTAL", 100, null, "total-" + subject);
        grant("ON_FIRST_USE_WINDOW", 100, 3600L, "win-" + subject);
        quota.reserve(request(60));
        assertThatThrownBy(() -> quota.reserve(request(60))).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.status()).isEqualTo(429);
        });
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_reservation", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.reservation_charge", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList(
                        "select held_units from pep_metering.quota_bucket order by generation", Long.class))
                .containsOnly(60L);
    }

    @Test
    void duplicateSettlementIsIdempotent() {
        grant("TOTAL", 100, null, "total-" + subject);
        var reservation = quota.reserve(request(20));
        quota.markDispatched(reservation.reservationId());
        quota.settle(reservation.reservationId(), usage(15), "provider-1", "{}");
        quota.settle(reservation.reservationId(), usage(15), "provider-1", "{}");

        assertThat(jdbc.queryForObject("select used_units from pep_metering.quota_bucket", Long.class))
                .isEqualTo(15L);
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.model_usage", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_ledger_entry", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void releasingUndispatchedReservationVoidsProvisionalWindow() {
        grant("ON_FIRST_USE_WINDOW", 100, 60L, "win-" + subject);
        var first = quota.reserve(request(10));
        quota.release(first.reservationId());
        assertThat(jdbc.queryForObject("select activation_state from pep_metering.quota_bucket", String.class))
                .isEqualTo("VOID");
        assertThat(jdbc.queryForObject("select current_bucket_id from pep_metering.quota_account", UUID.class))
                .isNull();

        var second = quota.reserve(request(10));
        assertThat(second.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.activationState()).isEqualTo("PROVISIONAL");
            assertThat(bucket.usedUnits()).isZero();
        });
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_bucket", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void lateSettlementOfOldWindowDoesNotTouchNewWindow() {
        grant("ON_FIRST_USE_WINDOW", 100, 60L, "win-" + subject);
        var old = quota.reserve(request(10));
        quota.markDispatched(old.reservationId());
        jdbc.update("""
                update pep_metering.quota_bucket
                   set start_at = now() - interval '2 minutes',
                       end_at = now() - interval '1 minute'
                """);

        var fresh = quota.reserve(request(10));
        quota.settle(old.reservationId(), usage(6), "provider-old", "{}");

        var buckets = jdbc.query(
                """
                select generation, used_units, held_units, activation_state
                  from pep_metering.quota_bucket order by generation
                """,
                (rs, rowNum) -> new BucketRow(
                        rs.getLong("generation"),
                        rs.getLong("used_units"),
                        rs.getLong("held_units"),
                        rs.getString("activation_state")));
        assertThat(buckets).containsExactly(new BucketRow(0, 6, 0, "ACTIVE"), new BucketRow(1, 0, 10, "PROVISIONAL"));
        assertThat(fresh.buckets())
                .singleElement()
                .satisfies(bucket -> assertThat(bucket.usedUnits()).isZero());
    }

    @Test
    void oneHundredConcurrentFirstCallsCreateExactlyOneWindow() throws Exception {
        grant("ON_FIRST_USE_WINDOW", 200, 3600L, "win-" + subject);
        int calls = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < calls; index++) {
            tasks.add(() -> {
                start.await(10, TimeUnit.SECONDS);
                quota.reserve(request(1));
                return null;
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(24)) {
            List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_bucket", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select held_units from pep_metering.quota_bucket", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject("select count(*) from pep_metering.quota_reservation", Long.class))
                .isEqualTo(100L);
    }

    @Test
    void repeatedGrantWithSameIdempotencyKeyDoesNotCreateNewRevision() {
        var request = new QuotaService.GrantRequest(
                "USER", subject, "TOTAL", 100, null, "key-" + subject, "test", "admin", "grant-1");
        var first = quota.grant(request);
        var second = quota.grant(request);
        assertThat(second).containsEntry("replayed", true);
        assertThat(second.get("revision")).isEqualTo(first.get("revision"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from pep_metering.quota_policy_version where policy_key = ?",
                        Long.class,
                        "key-" + subject))
                .isEqualTo(1L);
    }

    private void grant(String mode, long limit, Long duration, String policyKey) {
        quota.grant(new QuotaService.GrantRequest(
                "USER", subject, mode, limit, duration, policyKey, "test", "admin", "grant-" + UUID.randomUUID()));
    }

    private QuotaService.ReservationRequest request(long units) {
        return new QuotaService.ReservationRequest(
                UUID.randomUUID(),
                0,
                "hash-" + UUID.randomUUID(),
                "USER",
                subject,
                subject,
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                null,
                null,
                "deepseek-default",
                "deepseek",
                "deepseek-flash",
                units);
    }

    private static ModelUsage usage(long units) {
        return new ModelUsage(units / 2, units - units / 2, 0, 0, 0, units, "PROVIDER");
    }

    private record BucketRow(long generation, long used, long held, String state) {}
}
