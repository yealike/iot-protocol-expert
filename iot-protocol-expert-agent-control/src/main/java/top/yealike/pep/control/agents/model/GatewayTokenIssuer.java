package top.yealike.pep.control.agents.model;

import java.util.UUID;
import org.springframework.stereotype.Component;
import top.yealike.pep.control.config.ModelGatewayProperties;
import top.yealike.pep.model.contract.ModelGatewayCredentials;
import top.yealike.pep.model.contract.RunTokenCodec;
import top.yealike.pep.model.contract.RunTokenPayload;

/** 为一次 Run 或系统评测签发只对模型网关有效的短期凭据。 */
@Component
public class GatewayTokenIssuer {

    private final ModelGatewayProperties properties;

    public GatewayTokenIssuer(ModelGatewayProperties properties) {
        this.properties = properties;
    }

    public String issueForRun(String actor, UUID conversationId, UUID runId, String modelKey) {
        long exp = System.currentTimeMillis() / 1000 + Math.max(60, properties.getRunTokenTtlSeconds());
        RunTokenPayload payload = new RunTokenPayload(
                ModelGatewayCredentials.REF,
                actor,
                "USER",
                actor,
                properties.getDefaultTenantId(),
                properties.getDefaultProjectId(),
                conversationId == null ? null : conversationId.toString(),
                runId.toString(),
                modelKey,
                exp,
                UUID.randomUUID().toString());
        return RunTokenCodec.sign(payload, secret());
    }

    public String issueForEvaluator(String modelKey) {
        long exp = System.currentTimeMillis() / 1000 + Math.max(60, properties.getRunTokenTtlSeconds());
        RunTokenPayload payload = new RunTokenPayload(
                ModelGatewayCredentials.REF,
                properties.getEvaluatorSubject(),
                "SERVICE_ACCOUNT",
                "system:evaluator",
                properties.getDefaultTenantId(),
                properties.getDefaultProjectId(),
                null,
                null,
                modelKey,
                exp,
                UUID.randomUUID().toString());
        return RunTokenCodec.sign(payload, secret());
    }

    private String secret() {
        String secret = properties.getRunTokenSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置模型网关运行凭据签名密钥");
        }
        return secret;
    }
}
