package top.yealike.ipe.ops.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import top.yealike.ipe.ops.config.OpsProperties;

class AiContextCodecTest {
    private static final String SECRET = "test-only-shared-key";

    @Test
    void decodesOperationBoundContext() throws Exception {
        String encoded = encode("get_order_detail", """
                {"actor":"1","scope":"1,2","session":"session-id","expires":%d}
                """.formatted(Instant.now().plusSeconds(30).getEpochSecond()));
        var context = codec().decode("get_order_detail", encoded);

        assertEquals("1", context.actor());
        assertEquals("1,2", context.scope());
        assertEquals("session-id", context.session());
    }

    @Test
    void rejectsContextBoundToAnotherOperation() throws Exception {
        String encoded = encode("resolve_session", """
                {"actor":"1","expires":%d}
                """.formatted(Instant.now().plusSeconds(30).getEpochSecond()));

        assertThrows(GeneralSecurityException.class, () -> codec().decode("get_order_detail", encoded));
    }

    @Test
    void rejectsExpiredContext() throws Exception {
        String encoded = encode("get_order_detail", """
                {"actor":"1","expires":%d}
                """.formatted(Instant.now().minusSeconds(1).getEpochSecond()));

        assertThrows(IllegalArgumentException.class, () -> codec().decode("get_order_detail", encoded));
    }

    private static AiContextCodec codec() {
        var properties = new OpsProperties();
        properties.setAiMcpToken(SECRET);
        return new AiContextCodec(properties);
    }

    private static String encode(String operation, String claims) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        var key = MessageDigest.getInstance("SHA-256").digest(SECRET.getBytes(StandardCharsets.UTF_8));
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
        byte[] payload = cipher.doFinal(claims.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        java.nio.ByteBuffer.allocate(nonce.length + payload.length)
                                .put(nonce)
                                .put(payload)
                                .array());
    }
}
