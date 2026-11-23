package top.yealike.pep.ops.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.ops.config.properties.OpsProperties;

class AiContextCodecTest {

    private static final String SECRET = "test-context-secret";
    private static final String OPERATION = "resolve_device";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void consumesTheSameContextOnlyOnce() throws Exception {
        var clock = Clock.fixed(BASE, ZoneOffset.UTC);
        var codec = newCodec(clock, 10);
        String encoded = seal(OPERATION, BASE.getEpochSecond() + 30);

        assertThat(codec.decode(OPERATION, encoded).actor()).isEqualTo("42");
        assertThatThrownBy(() -> codec.decode(OPERATION, encoded)).isInstanceOf(AiContextConsumedException.class);
    }

    @Test
    void rejectsExpiredContext() {
        var clock = Clock.fixed(BASE, ZoneOffset.UTC);
        var codec = newCodec(clock, 10);

        assertThatThrownBy(() -> codec.decode(OPERATION, seal(OPERATION, BASE.getEpochSecond() - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Expired context");
    }

    @Test
    void purgesExpiredConsumptionRecordsBeforeRecordingNewContext() throws Exception {
        var clock = new MutableClock(BASE);
        var codec = newCodec(clock, 10);
        codec.decode(OPERATION, seal(OPERATION, BASE.getEpochSecond() + 30));
        assertThat(codec.consumedContextCount()).isEqualTo(1);

        clock.advanceSeconds(31);
        codec.decode(OPERATION, seal(OPERATION, clock.instant().getEpochSecond() + 30));

        assertThat(codec.consumedContextCount()).isEqualTo(1);
    }

    @Test
    void refusesNewContextsAtConsumptionCapacity() throws Exception {
        var clock = Clock.fixed(BASE, ZoneOffset.UTC);
        var codec = newCodec(clock, 1);
        codec.decode(OPERATION, seal(OPERATION, BASE.getEpochSecond() + 30));

        assertThatThrownBy(() -> codec.decode(OPERATION, seal(OPERATION, BASE.getEpochSecond() + 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Context consumption capacity exceeded");
    }

    private static AiContextCodec newCodec(Clock clock, int maxConsumedContexts) {
        var properties = new OpsProperties();
        properties.setAiMcpToken(SECRET);
        return new AiContextCodec(properties, clock, maxConsumedContexts);
    }

    private static String seal(String operation, long expiresAt) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key = MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
            byte[] claims = JsonMapper.shared()
                    .writeValueAsString(Map.of("expires", expiresAt, "actor", "42", "session", "session-1"))
                    .getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ByteBuffer.allocate(nonce.length + cipher.getOutputSize(claims.length))
                            .put(nonce)
                            .put(cipher.doFinal(claims))
                            .array());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
