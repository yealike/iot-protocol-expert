package top.yealike.pep.control.api.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.pep.control.api.internal.dto.Context;
import top.yealike.pep.control.config.properties.AiProperties;

/** 校验由 Runtime 注入、按工具名绑定的一次性可信调用上下文。 */
@Component
public class ControlAiContextCodec {

    private static final int MAX_CONTEXT_LENGTH = 4096;
    private static final int MIN_PACKED_LENGTH = 29;
    private static final int NONCE_LENGTH = 12;
    private static final int CONTEXT_TTL_SECONDS = 60;
    private static final int DEFAULT_MAX_CONSUMED_CONTEXTS = 10_000;

    private final AiProperties properties;
    private final Clock clock;
    private final int maxConsumedContexts;
    private final ConcurrentHashMap<String, Long> consumedContexts = new ConcurrentHashMap<>();

    @Autowired
    public ControlAiContextCodec(AiProperties properties) {
        this(properties, Clock.systemUTC(), DEFAULT_MAX_CONSUMED_CONTEXTS);
    }

    ControlAiContextCodec(AiProperties properties, Clock clock, int maxConsumedContexts) {
        this.properties = properties;
        this.clock = clock;
        if (maxConsumedContexts < 1) {
            throw new IllegalArgumentException("maxConsumedContexts must be positive");
        }
        this.maxConsumedContexts = maxConsumedContexts;
    }

    public Context decode(String operation, String encoded) throws GeneralSecurityException {
        String secret = properties.mcp() == null ? null : properties.mcp().token();
        if (secret == null
                || secret.isBlank()
                || encoded == null
                || encoded.isBlank()
                || encoded.length() > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid context");
        }
        byte[] packed = Base64.getUrlDecoder().decode(encoded);
        if (packed.length < MIN_PACKED_LENGTH) {
            throw new IllegalArgumentException("Invalid context");
        }
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Arrays.copyOf(packed, NONCE_LENGTH)));
        cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
        Map<?, ?> claims = JsonMapper.shared()
                .readValue(cipher.doFinal(packed, NONCE_LENGTH, packed.length - NONCE_LENGTH), Map.class);
        long now = Instant.now(clock).getEpochSecond();
        if (claims == null
                || !(claims.get("expires") instanceof Number expires)
                || expires.longValue() < now
                || expires.longValue() > now + CONTEXT_TTL_SECONDS) {
            throw new IllegalArgumentException("Expired context");
        }
        consume(encoded, expires.longValue(), now);
        return new Context(
                claims.get("actor") instanceof String actor ? actor : null,
                claims.get("session") instanceof String session ? session : null);
    }

    private void consume(String encoded, long expiresAt, long now) throws GeneralSecurityException {
        purgeExpired(now);
        if (consumedContexts.mappingCount() >= maxConsumedContexts) {
            throw new IllegalArgumentException("Context consumption capacity exceeded");
        }
        String digest = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8)));
        if (consumedContexts.putIfAbsent(digest, expiresAt) != null) {
            throw new ContextAlreadyConsumedException();
        }
    }

    private void purgeExpired(long now) {
        consumedContexts.forEach((digest, expiresAt) -> {
            if (expiresAt < now) {
                consumedContexts.remove(digest, expiresAt);
            }
        });
    }

    int consumedContextCount() {
        return consumedContexts.size();
    }

    public static final class ContextAlreadyConsumedException extends IllegalArgumentException {
        public ContextAlreadyConsumedException() {
            super("Context already consumed");
        }
    }
}
