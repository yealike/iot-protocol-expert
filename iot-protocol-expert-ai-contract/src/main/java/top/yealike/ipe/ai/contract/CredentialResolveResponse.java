package top.yealike.ipe.ai.contract;

import java.time.Instant;

public record CredentialResolveResponse(String credentialRef, long secretRevision, String secret, Instant expiresAt) {}
