package top.yealike.pep.control.integration.runtime;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import top.yealike.pep.control.config.properties.PlatformProperties;
import top.yealike.pep.control.config.properties.PlatformSecurity;
import top.yealike.pep.control.integration.runtime.dto.DispatchResult;
import top.yealike.pep.control.integration.runtime.dto.PrepareResult;
import top.yealike.pep.control.integration.runtime.dto.RuntimeTarget;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.observability.api.ApiResponse;
import top.yealike.pep.runtime.contract.InternalPaths;
import top.yealike.pep.runtime.contract.InternalSignature;
import top.yealike.pep.runtime.contract.ReleasePrepareRequest;
import top.yealike.pep.runtime.contract.ReleasePrepareResponse;
import top.yealike.pep.runtime.contract.RunDispatchReceipt;
import top.yealike.pep.runtime.contract.RunDispatchRequest;
import top.yealike.pep.runtime.contract.RuntimeRunStatus;

/** Control 到 Runtime 的签名 HTTP 客户端；只做传输与稳定错误映射，不持有 Run 状态。 */
@Component
public class RuntimeDispatchClient {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDispatchClient.class);
    private static final int MAX_BODY_CHARS = 4096;

    private final PlatformProperties properties;
    private final RestClient http;
    private final String secret;

    public RuntimeDispatchClient(PlatformProperties properties) {
        this.properties = properties;
        this.secret = resolveSecret(properties.security());
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().requestFactory(factory).build();
        if (secret.isBlank()) {
            log.warn("内部服务密钥未配置，Control 无法向 Runtime 派发任务");
        }
    }

    public List<RuntimeTarget> targets() {
        String baseUrl = properties.runtime().baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return List.of();
        }
        String normalized = baseUrl.strip().replaceFirst("/+$", "");
        return List.of(new RuntimeTarget(normalized, "direct", "direct"));
    }

    public PrepareResult prepare(RuntimeTarget target, ReleasePrepareRequest request) {
        String path = InternalPaths.RUNTIME_PREPARE;
        Response response = execute("POST", target, path, request);
        if (response.error() != null) {
            return new PrepareResult(false, "FAILED", null, response.error(), response.message(), response.status());
        }
        ReleasePrepareResponse value = readData(response.body(), ReleasePrepareResponse.class);
        if (value == null) {
            return new PrepareResult(
                    false, "FAILED", null, "CONTROL_EMPTY_RESPONSE", "Runtime 未返回发布准备结果", response.status());
        }
        return new PrepareResult(
                "READY".equalsIgnoreCase(value.state()),
                value.state(),
                value.instanceId(),
                null,
                value.message(),
                response.status());
    }

    public DispatchResult dispatch(RuntimeTarget target, String agentKey, String version, RunDispatchRequest request) {
        String path = InternalPaths.runtimeRuns(agentKey, version);
        Response response = execute("POST", target, path, request);
        if (response.status() == 429) {
            return new DispatchResult(false, 429, null, "RUN_BUSY", "Runtime 容量已满");
        }
        if (response.error() != null) {
            return new DispatchResult(false, response.status(), null, response.error(), response.message());
        }
        RunDispatchReceipt receipt = readData(response.body(), RunDispatchReceipt.class);
        return new DispatchResult(receipt != null && receipt.accepted(), response.status(), receipt, null, null);
    }

    public RuntimeRunStatus status(RuntimeTarget target, UUID runId, long executionToken) {
        String path = InternalPaths.runtimeRun(runId) + "?executionToken=" + executionToken;
        Response response = execute("GET", target, path, null);
        if (response.error() != null) {
            return null;
        }
        return readData(response.body(), RuntimeRunStatus.class);
    }

    public boolean cancel(RuntimeTarget target, UUID runId, long executionToken) {
        String path = InternalPaths.runtimeCancel(runId) + "?executionToken=" + executionToken;
        Response response = execute("POST", target, path, null);
        return response.error() == null || response.status() == 404;
    }

    private Response execute(String method, RuntimeTarget target, String path, Object body) {
        byte[] payload = body == null ? new byte[0] : JsonSupport.write(body).getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = InternalSignature.sign(secret, method, path, payload, timestamp, nonce, "runtime");
        try {
            RestClient.RequestBodySpec request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(URI.create(target.baseUrl() + path))
                    .header(
                            InternalSignature.HEADER_SERVICE,
                            properties.security().internalServiceName())
                    .header(
                            InternalSignature.HEADER_KEY_ID,
                            properties.security().internalKeyId())
                    .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                    .header(InternalSignature.HEADER_NONCE, nonce)
                    .header(InternalSignature.HEADER_SIGNATURE, signature)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (payload.length > 0) {
                request.contentType(MediaType.APPLICATION_JSON).body(payload);
            }
            byte[] responseBody = request.retrieve().body(byte[].class);
            String text = responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
            return new Response(200, text, null, null);
        } catch (RestClientResponseException exception) {
            String text = exception.getResponseBodyAsString();
            ErrorBody error = parseError(text);
            log.debug(
                    "runtime call failed: target={} status={} code={}",
                    target.baseUrl(),
                    exception.getStatusCode().value(),
                    error.code());
            return new Response(exception.getStatusCode().value(), text, error.code(), error.message());
        } catch (RuntimeException exception) {
            log.warn(
                    "runtime call unavailable: target={} path={} reason={}",
                    target.baseUrl(),
                    path,
                    safeMessage(exception));
            return new Response(503, "", "RUNTIME_UNAVAILABLE", "Runtime 暂时不可达");
        }
    }

    private static ErrorBody parseError(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_BODY_CHARS) {
            return new ErrorBody("CONTROL_ERROR", "Runtime 请求失败");
        }
        try {
            JsonNode body = JsonSupport.mapper().readTree(text);
            return new ErrorBody(
                    firstText(body, "CONTROL_ERROR", "code", "error"),
                    firstText(body, "Runtime 请求失败", "msg", "detail", "message"));
        } catch (RuntimeException exception) {
            return new ErrorBody("CONTROL_ERROR", "Runtime 请求失败");
        }
    }

    private static String firstText(JsonNode body, String fallback, String... fields) {
        if (body == null || body.isNull()) {
            return fallback;
        }
        for (String field : fields) {
            JsonNode value = body.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return fallback;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String resolveSecret(PlatformSecurity security) {
        if (security.internalServiceKey() != null
                && !security.internalServiceKey().isBlank()) {
            return security.internalServiceKey().strip();
        }
        return "";
    }

    private static <T> T readData(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var root = JsonSupport.mapper().readTree(text);
            if (root != null && root.has("code") && root.has("msg") && root.has("data")) {
                String code = root.path("code").asText();
                if (!ApiResponse.SUCCESS_CODE.equals(code)) {
                    return null;
                }
                var data = root.get("data");
                return data == null || data.isNull() ? null : JsonSupport.read(JsonSupport.write(data), type);
            }
            return JsonSupport.read(text, type);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record ErrorBody(String code, String message) {}

    private record Response(int status, String body, String error, String message) {}
}
