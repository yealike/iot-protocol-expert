package top.yealike.ipe.ai.platform.model;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import top.yealike.ipe.ai.contract.EndpointPolicy;
import top.yealike.ipe.ai.identity.UserIdentity;
import top.yealike.ipe.ai.platform.credential.CredentialService;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.Credential;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.ModelProfile;
import top.yealike.ipe.ai.platform.store.CredentialStore;
import top.yealike.ipe.ai.platform.store.ModelStore;
import top.yealike.ipe.ai.support.AiExceptions;
import top.yealike.ipe.ai.support.JsonSupport;

/** 模型 profile 管理：列表永不回显密钥，测试与保存分离。 */
@Service
public class ModelProfileService {

    private final ModelStore models;
    private final CredentialStore credentials;
    private final CredentialService credentialService;
    private final RestClient http;
    private final RestClient evaluationHttp;

    public ModelProfileService(
            ModelStore models,
            CredentialStore credentials,
            CredentialService credentialService,
            RestClient.Builder builder) {
        this.models = models;
        this.credentials = credentials;
        this.credentialService = credentialService;
        this.http = builder.build();
        var factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.evaluationHttp = builder.clone().requestFactory(factory).build();
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
        URI normalizedBaseUrl = validate(baseUrl, modelName, options);
        if (models.findByKey(key).isPresent()) {
            throw AiExceptions.conflict("REVISION_CONFLICT", "模型 key 已存在");
        }
        requireCredential(credentialId);
        return models.insert(
                key,
                displayName,
                description,
                "OPENAI_COMPAT",
                normalizedBaseUrl.toString(),
                modelName.strip(),
                credentialId,
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
        URI normalizedBaseUrl = validate(baseUrl, modelName, options);
        requireCredential(credentialId);
        int updated = models.update(
                id,
                expectedRevision,
                displayName,
                description,
                normalizedBaseUrl.toString(),
                modelName.strip(),
                credentialId,
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
        if (profile.credentialId() == null) {
            throw AiExceptions.unprocessable("MODEL_CREDENTIAL_MISSING", "模型未绑定凭据");
        }
        Credential credential = credentials
                .find(profile.credentialId())
                .orElseThrow(() -> AiExceptions.unprocessable("MODEL_CREDENTIAL_MISSING", "模型凭据不存在"));
        String secret = credentialService.resolve(credential.id(), credential.currentSecretRevision());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", profile.modelName());
        body.put("messages", List.of(Map.of("role", "user", "content", "只回复 OK")));
        body.put("max_tokens", 8);
        body.put("temperature", 0);
        long started = System.nanoTime();
        URI endpoint = modelEndpoint(profile.baseUrl());
        try {
            String response = http.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonSupport.write(body))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> result = JsonSupport.readMap(response);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", true);
            data.put("model", profile.modelName());
            data.put("latencyMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
            data.put("usage", result.get("usage"));
            data.put("note", "测试会产生少量模型 token 消耗");
            return data;
        } catch (org.springframework.web.client.HttpClientErrorException exception) {
            throw new AiExceptions.AiException(
                    exception.getStatusCode().value(),
                    "MODEL_TEST_FAILED",
                    "模型连通测试失败：" + exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            throw new AiExceptions.AiException(502, "MODEL_TEST_FAILED", "模型服务暂不可用");
        }
    }

    /** Run a bounded JSON-style completion for evaluator use without exposing provider credentials. */
    public String completeEvaluation(UUID id, String systemPrompt, String userPrompt) {
        ModelProfile profile = require(id);
        if (!profile.enabled() || profile.credentialId() == null) {
            throw AiExceptions.unprocessable("EVALUATOR_MODEL_UNAVAILABLE", "Judge 模型未启用或未绑定凭据");
        }
        Credential credential = credentials
                .find(profile.credentialId())
                .orElseThrow(() -> AiExceptions.unprocessable("EVALUATOR_MODEL_UNAVAILABLE", "Judge 模型凭据不存在"));
        String secret = credentialService.resolve(credential.id(), credential.currentSecretRevision());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", profile.modelName());
        body.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        body.put("max_tokens", 512);
        body.put("temperature", 0);
        try {
            URI endpoint = modelEndpoint(profile.baseUrl());
            String response = evaluationHttp
                    .post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonSupport.write(body))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> envelope = JsonSupport.readMap(response);
            Object rawChoices = envelope.get("choices");
            if (!(rawChoices instanceof List<?> choices)
                    || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)
                    || !(choice.get("message") instanceof Map<?, ?> message)
                    || !(message.get("content") instanceof String content)) {
                throw AiExceptions.unavailable("Judge 模型没有返回有效内容");
            }
            return content;
        } catch (org.springframework.web.client.RestClientResponseException exception) {
            throw AiExceptions.unavailable(
                    "Judge 模型请求失败：" + exception.getStatusCode().value());
        } catch (AiExceptions.AiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw AiExceptions.unavailable("Judge 模型暂不可用");
        }
    }

    private void requireCredential(UUID credentialId) {
        if (credentialId != null
                && credentials.find(credentialId).filter(Credential::enabled).isEmpty()) {
            throw AiExceptions.badRequest("凭据不存在或已停用");
        }
    }

    private static URI validate(String baseUrl, String modelName, Map<String, Object> options) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw AiExceptions.badRequest("模型 baseUrl 必须是 http(s) 地址");
        }
        URI uri;
        try {
            uri = EndpointPolicy.requireAllowed(baseUrl.strip());
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("模型 baseUrl " + exception.getMessage());
        }
        if (uri.getRawQuery() != null || uri.getFragment() != null) {
            throw AiExceptions.badRequest("模型 baseUrl 不能包含查询参数或片段");
        }
        if (modelName == null || modelName.isBlank()) {
            throw AiExceptions.badRequest("模型名称不能为空");
        }
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
        return uri;
    }

    private static URI modelEndpoint(String baseUrl) {
        URI base;
        try {
            base = EndpointPolicy.requireAllowedAndResolvable(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw AiExceptions.badRequest("模型 baseUrl " + exception.getMessage());
        }
        String value = base.toString();
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return URI.create(value + "/chat/completions");
    }
}
