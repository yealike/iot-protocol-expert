package top.yealike.pep.runtime.contract;

import java.util.UUID;

public record CredentialResolveRequest(
        UUID runId,
        long executionToken,
        String credentialRef,
        long secretRevision,
        String purpose,
        UUID publicationId) {}
