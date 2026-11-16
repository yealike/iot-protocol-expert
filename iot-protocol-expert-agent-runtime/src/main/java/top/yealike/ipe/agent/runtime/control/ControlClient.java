package top.yealike.ipe.agent.runtime.control;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import top.yealike.ipe.agent.runtime.config.RuntimeProperties;
import top.yealike.ipe.ai.contract.AgentManifest;
import top.yealike.ipe.ai.contract.CredentialResolveRequest;
import top.yealike.ipe.ai.contract.CredentialResolveResponse;
import top.yealike.ipe.ai.contract.InternalPaths;
import top.yealike.ipe.ai.contract.InternalSignature;
import top.yealike.ipe.ai.contract.McpDescriptor;
import top.yealike.ipe.ai.contract.RunClaimRequest;
import top.yealike.ipe.ai.contract.RunClaimResponse;
import top.yealike.ipe.ai.contract.RunCompletionRequest;
import top.yealike.ipe.ai.contract.RunContext;
import top.yealike.ipe.ai.contract.RunEventAck;
import top.yealike.ipe.ai.contract.RunEventBatch;
import top.yealike.ipe.ai.contract.SkillDescriptor;
import top.yealike.ipe.ai.contract.SkillFile;
import top.yealike.ipe.ai.contract.ToolAuthorizeRequest;
import top.yealike.ipe.ai.contract.ToolAuthorizeResponse;

/**
 * Control 内部接口客户端。
 *
 * <p>每个请求使用服务身份 HMAC 签名；敏感正文不写日志。HTTP 4xx/5xx 转成稳定业务异常。
 */
@Component
public class ControlClient {

    private static final Logger log = LoggerFactory.getLogger(ControlClient.class);

    private final RestClient http;
    private final ObjectMapper json;
    private final RuntimeProperties properties;
    private final String secret;

    public ControlClient(RestClient controlRestClient, ObjectMapper json, RuntimeProperties properties) {
        this.http = controlRestClient;
        this.json = json;
        this.properties = properties;
        this.secret = properties.resolvedServiceKey();
        if (secret.isBlank()) {
            log.warn("内部服务密钥未配置，Runtime 无法调用 Control；请注入 IPE_INTERNAL_SERVICE_KEY 或密钥文件");
        }
    }

    public AgentManifest manifest(UUID releaseId) {
        return get(InternalPaths.controlManifest(releaseId), AgentManifest.class);
    }

    public McpDescriptor mcpDescriptor(String key, String version) {
        return get(InternalPaths.controlMcpDescriptor(key, version), McpDescriptor.class);
    }

    public SkillDescriptor skillDescriptor(String key, String version) {
        return get(InternalPaths.controlSkill(key, version), SkillDescriptor.class);
    }

    public SkillFile skillFile(String key, String version, String path) {
        return get(InternalPaths.controlSkillFile(key, version, path), SkillFile.class);
    }

    public RunClaimResponse claim(RunClaimRequest request) {
        return post(InternalPaths.controlClaim(request.runId()), request, RunClaimResponse.class);
    }

    public RunClaimResponse heartbeat(RunClaimRequest request) {
        return post(InternalPaths.controlHeartbeat(request.runId()), request, RunClaimResponse.class);
    }

    public RunContext context(UUID runId, long executionToken) {
        return get(InternalPaths.controlContext(runId) + "?executionToken=" + executionToken, RunContext.class);
    }

    public RunEventAck events(RunEventBatch batch) {
        return post(InternalPaths.controlEvents(batch.runId()), batch, RunEventAck.class);
    }

    public void complete(RunCompletionRequest request) {
        post(InternalPaths.controlComplete(request.runId()), request, Map.class);
    }

    public ToolAuthorizeResponse authorize(ToolAuthorizeRequest request) {
        return post(InternalPaths.controlAuthorize(request.runId()), request, ToolAuthorizeResponse.class);
    }

    public CredentialResolveResponse resolveCredential(CredentialResolveRequest request) {
        return post(InternalPaths.controlCredentials(), request, CredentialResolveResponse.class);
    }

    public DesiredReleasePage desiredReleases() {
        return get(InternalPaths.controlDesiredReleases(), DesiredReleasePage.class);
    }

    public boolean readiness() {
        try {
            Map<?, ?> body = get(InternalPaths.controlReadiness(), Map.class);
            return body != null && !"DOWN".equalsIgnoreCase(String.valueOf(body.get("status")));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private <T> T get(String path, Class<T> type) {
        return execute("GET", path, null, type);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        return execute("POST", path, body, type);
    }

    private <T> T execute(String method, String path, Object body, Class<T> type) {
        byte[] payload =
                body == null ? new byte[0] : json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = InternalSignature.sign(secret, method, path, payload, timestamp, nonce, "control");
        try {
            RestClient.RequestBodySpec request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(URI.create(properties.controlBaseUrl() + path))
                    .header(InternalSignature.HEADER_SERVICE, properties.serviceName())
                    .header(InternalSignature.HEADER_KEY_ID, properties.serviceKeyId())
                    .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                    .header(InternalSignature.HEADER_NONCE, nonce)
                    .header(InternalSignature.HEADER_SIGNATURE, signature)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (payload.length > 0) {
                request.contentType(MediaType.APPLICATION_JSON).body(new String(payload, StandardCharsets.UTF_8));
            }
            return request.retrieve().body(type);
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            log.warn("control call failed: method={} path={} reason={}", method, path, safeMessage(exception));
            throw new ControlException(502, "CONTROL_UNAVAILABLE", "控制面暂时不可用：" + safeMessage(exception));
        }
    }

    private ControlException translate(RestClientResponseException exception) {
        String code = "CONTROL_" + exception.getStatusCode().value();
        String message = "控制面请求失败";
        try {
            Map<?, ?> body = json.readValue(exception.getResponseBodyAsString(), Map.class);
            if (body.get("error") != null) {
                code = String.valueOf(body.get("error"));
            }
            if (body.get("message") != null) {
                message = String.valueOf(body.get("message"));
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体只保留状态码，避免把 HTML 或内部页面写入日志
        }
        return new ControlException(exception.getStatusCode().value(), code, message);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
