package top.yealike.pep.runtime.contract;

import java.time.Instant;

public record CredentialResolveResponse(String credentialRef, long secretRevision, String secret, Instant expiresAt) {}
