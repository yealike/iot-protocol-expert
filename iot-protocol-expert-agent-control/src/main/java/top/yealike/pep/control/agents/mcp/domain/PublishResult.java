package top.yealike.pep.control.agents.mcp.domain;

import java.util.UUID;

public record PublishResult(UUID releaseId, UUID publicationId, String version, String catalogDigest) {}
