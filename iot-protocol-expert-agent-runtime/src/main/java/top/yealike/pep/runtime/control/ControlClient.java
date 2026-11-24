package top.yealike.pep.runtime.control;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import top.yealike.pep.observability.api.ApiResponse;
import top.yealike.pep.runtime.config.properties.RuntimeProperties;
import top.yealike.pep.runtime.contract.AgentManifest;
import top.yealike.pep.runtime.contract.ControlReadiness;
import top.yealike.pep.runtime.contract.CredentialResolveRequest;
import top.yealike.pep.runtime.contract.CredentialResolveResponse;
import top.yealike.pep.runtime.contract.DelegateTaskRequest;
import top.yealike.pep.runtime.contract.DelegateTaskResponse;
import top.yealike.pep.runtime.contract.DesiredReleasePage;
import top.yealike.pep.runtime.contract.InternalPaths;
import top.yealike.pep.runtime.contract.InternalSignature;
import top.yealike.pep.runtime.contract.McpDescriptor;
import top.yealike.pep.runtime.contract.OperationResult;
import top.yealike.pep.runtime.contract.RunClaimRequest;
import top.yealike.pep.runtime.contract.RunClaimResponse;
import top.yealike.pep.runtime.contract.RunCompletionRequest;
import top.yealike.pep.runtime.contract.RunContext;
import top.yealike.pep.runtime.contract.RunEventAck;
import top.yealike.pep.runtime.contract.RunEventBatch;
import top.yealike.pep.runtime.contract.SkillDescriptor;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.contract.ToolAuthorizeRequest;
import top.yealike.pep.runtime.contract.ToolAuthorizeResponse;
import top.yealike.pep.runtime.registry.NacosRuntimeRegistry;

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
    private final NacosRuntimeRegistry registry;
    private final String secret;

    @Autowired
    public ControlClient(
            RestClient controlRestClient,
            ObjectMapper json,
            RuntimeProperties properties,
            NacosRuntimeRegistry registry) {
        this.http = controlRestClient;
        this.json = json;
        this.properties = properties;
        this.registry = registry;
        this.secret = properties.resolvedServiceKey();
        warnIfSecretMissing();
    }

    public ControlClient(RestClient controlRestClient, ObjectMapper json, RuntimeProperties properties) {
        this(controlRestClient, json, properties, null);
    }

    private void warnIfSecretMissing() {
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

    /** 通过现有内部签名通道创建同实例子 Run。 */
    public DelegateTaskResponse submitDelegation(DelegateTaskRequest request) {
        return post(InternalPaths.CONTROL_A2A_TASKS, request, DelegateTaskResponse.class);
    }

    /** 以父执行权查询子任务结果。 */
    public DelegateTaskResponse delegation(UUID taskId, DelegateTaskRequest request) {
        return get(
                InternalPaths.CONTROL_A2A_TASKS + "/" + taskId + "?parentRunId=" + request.parentRunId()
                        + "&executionToken=" + request.executionToken()
                        + "&instanceId=" + URLEncoder.encode(request.instanceId(), StandardCharsets.UTF_8),
                DelegateTaskResponse.class);
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
        post(InternalPaths.controlComplete(request.runId()), request, OperationResult.class);
    }

    public ToolAuthorizeResponse authorize(ToolAuthorizeRequest request) {
        return post(InternalPaths.controlAuthorize(request.runId()), request, ToolAuthorizeResponse.class);
    }

    public CredentialResolveResponse resolveCredential(CredentialResolveRequest request) {
        return post(InternalPaths.controlCredentials(), request, CredentialResolveResponse.class);
    }

    public DesiredReleasePage desiredReleases() {
        return desiredReleases(0L);
    }

    public DesiredReleasePage desiredReleases(long cursor) {
        return get(InternalPaths.controlDesiredReleases(cursor, 200), DesiredReleasePage.class);
    }

    public boolean readiness() {
        try {
            ControlReadiness body = get(InternalPaths.controlReadiness(), ControlReadiness.class);
            return body != null && !"DOWN".equalsIgnoreCase(body.status());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private <T> T get(String path, Class<T> type) {
        return execute(
                properties.controlServiceName(), baseUrl(properties.controlServiceName()), "GET", path, null, type);
    }

    private <T> T post(String path, Object body, Class<T> type) {
        return execute(
                properties.controlServiceName(), baseUrl(properties.controlServiceName()), "POST", path, body, type);
    }

    private <T> T execute(String serviceName, String baseUrl, String method, String path, Object body, Class<T> type) {
        byte[] payload =
                body == null ? new byte[0] : json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String signature = InternalSignature.sign(secret, method, path, payload, timestamp, nonce, "control");
        try {
            RestClient.RequestBodySpec request = http.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(URI.create(baseUrl + path))
                    .header(InternalSignature.HEADER_SERVICE, properties.serviceName())
                    .header(InternalSignature.HEADER_KEY_ID, properties.serviceKeyId())
                    .header(InternalSignature.HEADER_TIMESTAMP, timestamp)
                    .header(InternalSignature.HEADER_NONCE, nonce)
                    .header(InternalSignature.HEADER_SIGNATURE, signature)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (payload.length > 0) {
                request.contentType(MediaType.APPLICATION_JSON).body(new String(payload, StandardCharsets.UTF_8));
            }
            String text = request.retrieve().body(String.class);
            return unwrap(text, type);
        } catch (ControlException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            log.warn("control call failed: method={} path={} reason={}", method, path, safeMessage(exception));
            throw new ControlException(502, "CONTROL_UNAVAILABLE", "控制面暂时不可用：" + safeMessage(exception));
        }
    }

    private <T> T unwrap(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var root = json.readTree(text);
            if (root != null && root.has("code") && root.has("msg") && root.has("data")) {
                String code = root.path("code").isTextual()
                        ? root.path("code").textValue()
                        : root.path("code").asText();
                String message = root.path("msg").isTextual() ? root.path("msg").textValue() : "控制面请求失败";
                if (!ApiResponse.SUCCESS_CODE.equals(code)) {
                    throw new ControlException(502, code == null || code.isBlank() ? "CONTROL_ERROR" : code, message);
                }
                var data = root.get("data");
                return data == null || data.isNull() ? null : json.readValue(json.writeValueAsString(data), type);
            }
            return json.readValue(text, type);
        } catch (ControlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ControlException(502, "CONTROL_RESPONSE_INVALID", "控制面返回了无法解析的响应");
        }
    }

    private String baseUrl(String serviceName) {
        if (registry != null && registry.enabled()) {
            String discovered = registry.discoverBaseUrl(serviceName);
            if (discovered != null) {
                return discovered;
            }
            String code = properties.controlServiceName().equals(serviceName)
                    ? "CONTROL_UNAVAILABLE"
                    : "COMPONENT_UNAVAILABLE";
            throw new ControlException(503, code, "注册中心没有可用的组件实例：" + serviceName);
        }
        return properties.controlBaseUrl();
    }

    private ControlException translate(RestClientResponseException exception) {
        String code = "CONTROL_" + exception.getStatusCode().value();
        String message = "控制面请求失败";
        try {
            var body = json.readTree(exception.getResponseBodyAsString());
            if (body.path("code").isTextual()) {
                code = body.path("code").textValue();
            } else if (body.path("error").isTextual()) {
                code = body.path("error").textValue();
            }
            if (body.path("msg").isTextual()) {
                message = body.path("msg").textValue();
            } else if (body.path("detail").isTextual()) {
                message = body.path("detail").textValue();
            } else if (body.path("message").isTextual()) {
                message = body.path("message").textValue();
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
