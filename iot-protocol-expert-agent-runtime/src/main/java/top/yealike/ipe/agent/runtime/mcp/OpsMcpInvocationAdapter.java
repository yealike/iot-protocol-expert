package top.yealike.ipe.agent.runtime.mcp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.yealike.ipe.ai.contract.McpDescriptor;

/**
 * OPS 专属的 tools/call 上下文封装。
 *
 * <p>OPS 的 REST-to-MCP 工具要求 sealed {@code _ipe_context}，该字段永远不进入模型可见 Schema。
 * 其他 MCP 不转发 Cookie/上下文，避免把 OPS 的验签方式泛化到第三方服务。
 */
public final class OpsMcpInvocationAdapter {

    public static final String CONTEXT_FIELD = "_ipe_context";
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int MAX_CONTEXT_SECONDS = 45;

    private OpsMcpInvocationAdapter() {}

    public static boolean applies(McpDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        Object mode = descriptor.endpointPolicy().get("contextMode");
        if ("OPS".equalsIgnoreCase(String.valueOf(mode))) {
            return true;
        }
        Object authMode = descriptor.endpointPolicy().get("authMode");
        if ("OPS_CONTEXT".equalsIgnoreCase(String.valueOf(authMode))) {
            return true;
        }
        // 兼容存量 ops 组件：MCP key 固定为 ops，且端点由 ipe-ops-rest 提供。
        return "ops".equalsIgnoreCase(descriptor.key());
    }

    public static String seal(String operation, String actor, String scope, String secret) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("MCP 工具名不能为空");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("OPS 调用上下文密钥不可用");
        }
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("expires", Instant.now().plusSeconds(MAX_CONTEXT_SECONDS).getEpochSecond());
            if (actor != null && !actor.isBlank()) {
                claims.put("actor", actor);
            }
            if (scope != null && !scope.isBlank()) {
                claims.put("scope", scope);
            }
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(JSON.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
            byte[] packed = java.nio.ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("OPS 调用上下文签名失败", exception);
        }
    }
}
