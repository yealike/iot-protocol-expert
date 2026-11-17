package top.yealike.pep.model.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 运行凭据的紧凑编码：base64url(字段串) + "." + base64url(HMAC-SHA256)。
 *
 * <p>这是内部短期凭据，不替代 OIDC 登录；受众、过期时间与主体都由控制服务签发。
 */
public final class RunTokenCodec {

    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RunTokenCodec() {}

    public static String sign(RunTokenPayload payload, String secret) {
        String body = encodeBody(payload);
        String encoded = ENCODER.encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(hmac(encoded, secret));
    }

    public static RunTokenPayload verify(String token, String secret, long nowEpochSeconds) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("缺少运行凭据");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new IllegalArgumentException("运行凭据格式非法");
        }
        String encoded = token.substring(0, dot);
        byte[] signature;
        try {
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("运行凭据格式非法");
        }
        if (!MessageDigest.isEqual(signature, hmac(encoded, secret))) {
            throw new IllegalArgumentException("运行凭据签名无效");
        }
        String body = new String(DECODER.decode(encoded), StandardCharsets.UTF_8);
        RunTokenPayload payload = decodeBody(body);
        if (payload.exp() <= nowEpochSeconds) {
            throw new IllegalArgumentException("运行凭据已过期");
        }
        return payload;
    }

    public static String fingerprint(RunTokenPayload payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encodeBody(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String encodeBody(RunTokenPayload payload) {
        String[] values = {
            payload.aud(),
            payload.sub(),
            payload.scopeKind(),
            payload.actor(),
            payload.tenant(),
            payload.project(),
            payload.task(),
            payload.run(),
            payload.modelProfile(),
            Long.toString(payload.exp()),
            payload.jti()
        };
        StringBuilder body = new StringBuilder(256);
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                body.append('\n');
            }
            body.append(escape(values[index]));
        }
        return body.toString();
    }

    private static RunTokenPayload decodeBody(String body) {
        String[] parts = body.split("\n", -1);
        if (parts.length != 11) {
            throw new IllegalArgumentException("运行凭据内容不完整");
        }
        try {
            return new RunTokenPayload(
                    unescape(parts[0]),
                    unescape(parts[1]),
                    unescape(parts[2]),
                    unescape(parts[3]),
                    unescape(parts[4]),
                    unescape(parts[5]),
                    unescape(parts[6]),
                    unescape(parts[7]),
                    unescape(parts[8]),
                    Long.parseLong(parts[9]),
                    unescape(parts[10]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("运行凭据内容不完整");
        }
    }

    private static byte[] hmac(String value, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置运行凭据签名密钥");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("运行凭据签名失败", exception);
        }
    }

    private static String escape(String value) {
        return value == null
                ? ""
                : value.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D");
    }

    private static String unescape(String value) {
        return value.replace("%0D", "\r").replace("%0A", "\n").replace("%25", "%");
    }
}
