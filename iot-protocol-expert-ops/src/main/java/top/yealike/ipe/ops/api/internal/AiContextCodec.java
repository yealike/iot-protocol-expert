package top.yealike.ipe.ops.api.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.ipe.ops.config.OpsProperties;

/** Validates the operation-bound AI context supplied by the MCP gateway. */
@Component
public class AiContextCodec {
    private static final int MAX_CONTEXT_LENGTH = 4096;
    private static final int MIN_PACKED_LENGTH = 29;

    private final OpsProperties properties;

    public AiContextCodec(OpsProperties properties) {
        this.properties = properties;
    }

    public Context decode(String operation, Object encoded) throws GeneralSecurityException {
        String secret = properties.getAiMcpToken();
        if (secret.isBlank() || !(encoded instanceof String value) || value.length() > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid context");
        }
        byte[] packed = Base64.getUrlDecoder().decode(value);
        if (packed.length < MIN_PACKED_LENGTH) throw new IllegalArgumentException("Invalid context");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        var key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
        cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
        Map<?, ?> claims =
                JsonMapper.shared().readValue(cipher.doFinal(packed, 12, packed.length - 12), Map.class);
        long now = Instant.now().getEpochSecond();
        if (claims == null
                || !(claims.get("expires") instanceof Number expires)
                || expires.longValue() < now
                || expires.longValue() > now + 60) {
            throw new IllegalArgumentException("Expired context");
        }
        return new Context(
                claims.get("actor") instanceof String actor ? actor : null,
                claims.get("scope") instanceof String scope ? scope.strip() : null,
                claims.get("session") instanceof String session ? session : null);
    }

    public record Context(String actor, String scope, String session) {}
}
