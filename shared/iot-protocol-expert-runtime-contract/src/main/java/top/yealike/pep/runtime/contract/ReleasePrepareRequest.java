package top.yealike.pep.runtime.contract;

import java.util.UUID;

public record ReleasePrepareRequest(UUID releaseId, String manifestDigest, long generation, UUID publicationId) {}
