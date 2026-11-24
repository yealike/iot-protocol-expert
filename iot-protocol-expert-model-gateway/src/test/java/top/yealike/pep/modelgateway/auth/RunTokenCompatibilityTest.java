package top.yealike.pep.modelgateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import top.yealike.pep.model.contract.RunTokenCodec;
import top.yealike.pep.model.contract.RunTokenLineage;
import top.yealike.pep.model.contract.RunTokenPayload;

class RunTokenCompatibilityTest {

    private static final String FIXTURE_SECRET = "test-fixture-only";

    @Test
    void verifiesBothWireVersions() {
        var legacy = payload(null);
        var child = payload(new RunTokenLineage("root", "parent", "task", 2, 50_000));
        String oldToken = RunTokenCodec.sign(legacy, FIXTURE_SECRET);
        String childToken = RunTokenCodec.sign(child, FIXTURE_SECRET);

        assertThat(oldToken.split("\\.")).hasSize(2);
        assertThat(childToken).startsWith("v2.");
        assertThat(RunTokenCodec.verify(oldToken, FIXTURE_SECRET, 50)).isEqualTo(legacy);
        assertThat(RunTokenCodec.verify(childToken, FIXTURE_SECRET, 50)).isEqualTo(child);
        assertThat(RunTokenCodec.fingerprint(child)).isNotEqualTo(RunTokenCodec.fingerprint(legacy));
    }

    @Test
    void bindsWireVersionAndExpirationToSignature() {
        var child = payload(new RunTokenLineage("root", "parent", "task", 2, 50_000));
        var encoded = RunTokenCodec.sign(child, FIXTURE_SECRET);

        assertThatThrownBy(() -> RunTokenCodec.verify(encoded.substring(3), FIXTURE_SECRET, 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RunTokenCodec.verify(encoded, FIXTURE_SECRET, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunTokenPayload payload(RunTokenLineage lineage) {
        return new RunTokenPayload(
                "model-gateway",
                "subject",
                "USER",
                "subject",
                "tenant",
                "project",
                "conversation",
                "run",
                "model",
                100,
                "nonce",
                lineage);
    }
}
