package top.yealike.pep.modelgateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import top.yealike.pep.modelgateway.config.GatewayProperties;

class QuotaSnapshotTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final String SUBJECT = "1";

    private final QuotaRepository repository = mock(QuotaRepository.class);
    private final QuotaService service =
            new QuotaService(repository, mock(TransactionTemplate.class), new GatewayProperties());

    @Test
    void availabilityUsesTheTightestPolicyAndProjectsTheNextWindow() {
        var total = account("total", "TOTAL", "ACTIVE", 100, 20, 10, null, null);
        var window = account("window", "ON_FIRST_USE_WINDOW", "ACTIVE", 50, 50, 0, NOW, null);
        when(repository.now()).thenReturn(NOW);
        when(repository.accounts("USER", SUBJECT)).thenReturn(List.of(total, window));

        assertThat(service.snapshot("USER", SUBJECT)).containsEntry("availableUnits", 50L);

        when(repository.accounts("USER", SUBJECT)).thenReturn(List.of(total));
        assertThat(service.snapshot("USER", SUBJECT)).containsEntry("availableUnits", 70L);
    }

    @Test
    void disabledAndExpiredPoliciesBlockTheWholeAccount() {
        var active = account("total", "TOTAL", "ACTIVE", 100, 0, 0, null, null);
        var disabled = account("disabled", "TOTAL", "DISABLED", 100, 0, 0, null, null);
        var expired = account("expired", "TOTAL", "ACTIVE", 100, 0, 0, null, NOW);
        when(repository.now()).thenReturn(NOW);
        when(repository.accounts("USER", SUBJECT))
                .thenReturn(List.of(active, disabled), List.of(active, expired), List.of());

        assertThat(service.snapshot("USER", SUBJECT)).containsEntry("availableUnits", 0L);
        assertThat(service.snapshot("USER", SUBJECT)).containsEntry("availableUnits", 0L);
        assertThat(service.snapshot("USER", SUBJECT)).containsEntry("availableUnits", 0L);
    }

    private QuotaRecords.Account account(
            String key,
            String mode,
            String state,
            long limit,
            long used,
            long held,
            OffsetDateTime endAt,
            OffsetDateTime expiresAt) {
        UUID accountId = UUID.randomUUID();
        UUID bucketId = UUID.randomUUID();
        var policy = new QuotaRecords.Policy(UUID.randomUUID(), key, 1, mode, limit, null, NOW.minusDays(1), expiresAt);
        var bucket = new QuotaRecords.Bucket(
                bucketId, accountId, 1, 1, NOW.minusHours(1), endAt, "ACTIVE", limit, used, held);
        when(repository.findPolicy(key, 1)).thenReturn(Optional.of(policy));
        when(repository.findBucket(bucketId)).thenReturn(Optional.of(bucket));
        return new QuotaRecords.Account(accountId, "USER", SUBJECT, key, 1, bucketId, state, 0, 0);
    }
}
