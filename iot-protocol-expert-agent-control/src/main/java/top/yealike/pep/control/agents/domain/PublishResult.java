package top.yealike.pep.control.agents.domain;

import java.util.UUID;

public record PublishResult(UUID releaseId, UUID publicationId, String version, String manifestDigest) {}
