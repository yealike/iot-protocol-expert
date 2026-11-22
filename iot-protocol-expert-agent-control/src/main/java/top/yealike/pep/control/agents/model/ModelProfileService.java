package top.yealike.pep.control.agents.model;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.yealike.pep.control.agents.credential.CredentialService;
import top.yealike.pep.control.agents.domain.Credential;
import top.yealike.pep.control.agents.domain.ModelProfile;
import top.yealike.pep.control.agents.store.CredentialStore;
import top.yealike.pep.control.agents.store.ModelStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 模型 profile 管理：列表永不回显密钥，测试与保存分离。 */
@Service
public class ModelProfileService {

    private final ModelStore models;
    private final CredentialStore credentials;
    private final CredentialService credentialService;
    private final GatewayTokenIssuer gatewayTokens;
    private final ModelGatewayClient gateway;
    private final top.yealike.pep.control.config.ModelGatewayProperties gatewayProperties;
    private final RestClient http;

    public ModelProfileService(
            ModelStore models,
            CredentialStore credentials,
            CredentialService credentialService,
            GatewayTokenIssuer gatewayTokens,
            ModelGatewayClient gateway,
            top.yealike.pep.control.config.ModelGatewayProperties gatewayProperties,
            RestClient.Builder builder) {
        this.models = models;
        this.credentials = credentials;
        this.credentialService = credentialService;
        this.gatewayTokens = gatewayTokens;
        this.gateway = gateway;
        this.gatewayProperties = gatewayProperties;
        this.http = builder.build();
    }

    public List<ModelProfile> list() {
        return models.list();
    }

    public ModelProfile require(UUID id) {
        return models.find(id).orElseThrow(() -> AiExceptions.notFound("模型不存在"));
    }

    public ModelProfile requireByKey(String key) {
        return models.findByKey(key).orElseThrow(() -> AiExceptions.notFound("模型不存在：" + key));
    }

    public UUID create(
            UserIdentity actor,
            String key,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            UUID credentialId,
            Map<String, Object> options) {
        CredentialService.requireKey(key);
        validateOptions(options);
        if (models.findByKey(key).isPresent()) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "模型 key 已存在");
        }
        return models.insert(
                key,
                displayName,
                description,
                "OPENAI_COMPAT",
                gatewayBaseUrl(),
                key,
                null,
                JsonSupport.write(options == null ? Map.of() : options),
                actor.username());
    }

    public ModelProfile update(
            UUID id,
            long expectedRevision,
            String displayName,
            String description,
            String baseUrl,
            String modelName,
            UUID credentialId,
            Map<String, Object> options,
            boolean enabled) {
        require(id);
        validateOptions(options);
        int updated = models.update(
                id,
                expectedRevision,
                displayName,
                description,
                gatewayBaseUrl(),
                models.find(id).orElseThrow().key(),
                null,
                JsonSupport.write(options == null ? Map.of() : options),
                enabled);
        if (updated == 0) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "模型已被其他请求修改，请刷新后重试");
        }
        return require(id);
    }

    public Map<String, Object> test(UUID id) {
        ModelProfile profile = require(id);
        if (!profile.enabled()) {
            throw AiExceptions.conflict("MODEL_DISABLED", "模型已停用");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", profile.key());
        body.put("messages", List.of(Map.of("role", "user", "content", "只回复 OK")));
        body.put("max_tokens", 8);
        body.put("temperature", 0);
        long started = System.nanoTime();
        try {
            Map<String, Object> result = gateway.chat(gatewayTokens.issueForEvaluator(profile.key()), body);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", true);
            data.put("model", profile.key());
            data.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            data.put("usage", result.get("usage"));
            data.put("note", "测试会产生少量模型 token 消耗，并按评测服务账号计量");
            return data;
        } catch (AiExceptions.AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiExceptions.AiException(502, "MODEL_TEST_FAILED", "模型服务暂不可用");
        }
    }

    private void requireCredential(UUID credentialId) {
        if (credentialId != null
                && credentials.find(credentialId).filter(Credential::enabled).isEmpty()) {
            throw AiExceptions.badRequest("凭据不存在或已停用");
        }
    }

    private static void validateOptions(Map<String, Object> options) {
        if (options != null) {
            Object temperature = options.get("temperature");
            if (temperature instanceof Number number && (number.doubleValue() < 0 || number.doubleValue() > 2)) {
                throw AiExceptions.badRequest("temperature 必须在 0-2 之间");
            }
            Object maxTokens = options.get("maxTokens");
            if (maxTokens instanceof Number number && (number.intValue() < 1 || number.intValue() > 131072)) {
                throw AiExceptions.badRequest("maxTokens 必须在 1-131072 之间");
            }
        }
    }

    private String gatewayBaseUrl() {
        String base = gatewayProperties.getBaseUrl();
        if (base == null || base.isBlank()) {
            return "http://127.0.0.1:19085/v1";
        }
        String value = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return value.endsWith("/v1") ? value : value + "/v1";
    }
}
