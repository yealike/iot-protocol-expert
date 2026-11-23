package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 凭据摘要；始终不包含密钥明文。 */
public record CredentialSummary(
        UUID id,
        String key,
        String displayName,
        long currentSecretRevision,
        boolean enabled,
        long revision,
        String masked,
        String keyId,
        Instant createdAt,
        Instant updatedAt) {

    public static CredentialSummary from(Credential credential, String keyId) {
        return new CredentialSummary(
                credential.id(),
                credential.key(),
                credential.displayName(),
                credential.currentSecretRevision(),
                credential.enabled(),
                credential.revision(),
                "******",
                keyId,
                credential.createdAt(),
                credential.updatedAt());
    }
}
