package top.yealike.ipe.ai.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Control 与 Runtime 的服务间 HMAC-SHA256 签名。
 *
 * <p>签名覆盖 method、规范 path+query、body SHA-256、timestamp、nonce、audience。时间窗与 nonce
 * 防重放由 Control 侧持久化校验。
 */
public final class InternalSignature {

    public static final String HEADER_SERVICE = "X-IPE-Service";
    public static final String HEADER_KEY_ID = "X-IPE-Key-Id";
    public static final String HEADER_TIMESTAMP = "X-IPE-Timestamp";
    public static final String HEADER_NONCE = "X-IPE-Nonce";
    public static final String HEADER_SIGNATURE = "X-IPE-Signature";

    private InternalSignature() {}

    public static String sign(
            String secret,
            String method,
            String pathAndQuery,
            byte[] body,
            String timestamp,
            String nonce,
            String audience) {
        String canonical = canonical(method, pathAndQuery, body, timestamp, nonce, audience);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot sign internal request", exception);
        }
    }

    public static boolean verify(
            String secret,
            String expected,
            String method,
            String pathAndQuery,
            byte[] body,
            String timestamp,
            String nonce,
            String audience) {
        if (secret == null || secret.isBlank() || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(secret, method, pathAndQuery, body, timestamp, nonce, audience)
                        .getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    public static String bodySha256(byte[] body) {
        return Digests.sha256Hex(body == null ? new byte[0] : body);
    }

    private static String canonical(
            String method, String pathAndQuery, byte[] body, String timestamp, String nonce, String audience) {
        return String.join(
                "\n",
                method == null ? "" : method.toUpperCase(Locale.ROOT),
                pathAndQuery == null ? "" : pathAndQuery,
                bodySha256(body),
                timestamp == null ? "" : timestamp,
                nonce == null ? "" : nonce,
                audience == null ? "" : audience);
    }
}
