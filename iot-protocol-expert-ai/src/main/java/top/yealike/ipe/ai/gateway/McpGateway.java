package top.yealike.ipe.ai.gateway;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.ai.config.AiProperties;
import top.yealike.ipe.ai.support.JsonSupport;

/** Client for the stateless Streamable HTTP endpoint routed by Higress. */
@Component
public class McpGateway {
    private static final String VERSION = "2025-06-18";
    private static final int MCP_SESSION_LOST_ERROR = -32081;
    private static final int HTTP_SESSION_LOST_ERROR = -32082;
    private static final java.util.regex.Pattern HIGRESS_HTTP_ERROR =
            java.util.regex.Pattern.compile("^call failed, status: ([45][0-9]{2})(?:,|$)");
    private final RestClient http;
    private final McpEndpointResolver endpoints;
    private final String token;
    private volatile URI sessionEndpoint;
    private volatile boolean sessionValid;
    private final SecureRandom random = new SecureRandom();

    public McpGateway(AiProperties properties, McpEndpointResolver endpoints, RestClient.Builder builder) {
        http = builder.build();
        this.endpoints = endpoints;
        token = properties.mcp().token();
    }

    public List<?> tools() {
        Object tools = request("tools/list", Map.of(), Map.of()).get("tools");
        if (!(tools instanceof List<?> list)) throw new IllegalStateException("Invalid MCP tool list");
        return list;
    }

    public Map<String, Object> call(String name, Map<String, Object> arguments, Map<String, String> headers) {
        var args = new LinkedHashMap<>(arguments);
        args.put("_ipe_context", seal(name, headers));
        var result = request("tools/call", Map.of("name", name, "arguments", args), Map.of());
        Map<String, Object> data;
        if (result.get("structuredContent") instanceof Map<?, ?> structured) {
            data = object(structured);
        } else {
            if (!(result.get("content") instanceof List<?> content) || content.size() != 1) {
                throw new IllegalStateException("Invalid MCP content");
            }
            String text = String.valueOf(object(content.getFirst()).get("text"));
            try {
                data = JsonSupport.readMap(text);
            } catch (RuntimeException ex) {
                if (Boolean.TRUE.equals(result.get("isError"))) {
                    var status = HIGRESS_HTTP_ERROR.matcher(text);
                    throw new ToolFailure(status.find() ? Integer.parseInt(status.group(1)) : 502);
                }
                throw ex;
            }
        }
        if (Boolean.TRUE.equals(result.get("isError")) || data.containsKey("status")) {
            int status = data.get("status") instanceof Number number ? number.intValue() : 502;
            throw new ToolFailure(status);
        }
        return data;
    }

    private String seal(String operation, Map<String, String> headers) {
        try {
            if (token.isBlank()) throw new IllegalStateException("MCP token is missing");
            var claims = new LinkedHashMap<String, Object>();
            claims.put("expires", Instant.now().plusSeconds(45).getEpochSecond());
            if (headers.containsKey("X-Ai-Actor")) claims.put("actor", headers.get("X-Ai-Actor"));
            if (headers.containsKey("X-Ai-Station-Scope")) claims.put("scope", headers.get("X-Ai-Station-Scope"));
            if (headers.containsKey("Cookie")) {
                for (String part : headers.get("Cookie").split(";")) {
                    String cookie = part.strip();
                    if (cookie.startsWith("JSESSIONID=")) {
                        claims.put("session", cookie.substring("JSESSIONID=".length()));
                        break;
                    }
                }
            }
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            var key = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(operation.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(JsonSupport.write(claims).getBytes(StandardCharsets.UTF_8));
            byte[] packed = java.nio.ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(packed);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot authenticate MCP operation", ex);
        }
    }

    private Map<String, Object> request(String method, Map<String, Object> params, Map<String, String> headers) {
        try {
            return exchange(initialize(), method, params, headers);
        } catch (McpSessionLostException ex) {
            invalidateSession();
            return exchange(initialize(), method, params, headers);
        }
    }

    private synchronized URI initialize() {
        URI endpoint = endpoints.endpoint();
        if (sessionValid && endpoint.equals(sessionEndpoint)) return endpoint;
        var response = exchange(
                endpoint,
                "initialize",
                Map.of(
                        "protocolVersion",
                        VERSION,
                        "capabilities",
                        Map.of(),
                        "clientInfo",
                        Map.of("name", "ipe-ai", "version", "1.0.0")),
                Map.of());
        if (!VERSION.equals(response.get("protocolVersion"))) {
            throw new IllegalStateException("Unsupported MCP version");
        }
        post(endpoint, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), Map.of())
                .retrieve()
                .toBodilessEntity();
        sessionEndpoint = endpoint;
        sessionValid = true;
        return endpoint;
    }

    private synchronized void invalidateSession() {
        sessionValid = false;
    }

    private Map<String, Object> exchange(
            URI endpoint, String method, Map<String, Object> params, Map<String, String> headers) {
        String id = UUID.randomUUID().toString();
        String body = post(endpoint, Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params), headers)
                .retrieve()
                .body(String.class);
        var reply = JsonSupport.readMap(body);
        if (reply.containsKey("error")) {
            Object error = reply.get("error");
            int code = error instanceof Map<?, ?> map
                    && map.get("code") instanceof Number number ? number.intValue() : -1;
            if (code == MCP_SESSION_LOST_ERROR || code == HTTP_SESSION_LOST_ERROR) {
                throw new McpSessionLostException();
            }
        }
        if (!"2.0".equals(reply.get("jsonrpc")) || !id.equals(reply.get("id")) || reply.containsKey("error")) {
            throw new IllegalStateException("Invalid MCP response");
        }
        return object(reply.get("result"));
    }

    private RestClient.RequestBodySpec post(URI endpoint, Map<String, Object> message, Map<String, String> headers) {
        var request = http.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .header("MCP-Protocol-Version", VERSION);
        headers.forEach(request::header);
        return request.body(message);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object data) {
        if (!(data instanceof Map<?, ?>)) throw new IllegalStateException("Invalid MCP response object");
        return (Map<String, Object>) data;
    }

    public static final class ToolFailure extends RuntimeException {
        private final int status;

        ToolFailure(int status) {
            super("MCP tool failed (" + status + ")");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final class McpSessionLostException extends RuntimeException {
        private McpSessionLostException() {
            super("MCP session is no longer valid");
        }
    }
}
