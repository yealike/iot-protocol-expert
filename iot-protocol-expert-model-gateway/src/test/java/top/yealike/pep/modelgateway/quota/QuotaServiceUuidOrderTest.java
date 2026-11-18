package top.yealike.pep.modelgateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuotaServiceUuidOrderTest {

    @Test
    void uuidOrderMatchesPostgresqlUnsignedOrdering() {
        UUID highBitsSet = UUID.fromString("80000000-0000-0000-0000-000000000001");
        UUID lowBitsSet = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(List.of(highBitsSet, lowBitsSet).stream()
                        .sorted(QuotaService.databaseUuidOrder())
                        .toList())
                .containsExactly(lowBitsSet, highBitsSet);
    }
}
