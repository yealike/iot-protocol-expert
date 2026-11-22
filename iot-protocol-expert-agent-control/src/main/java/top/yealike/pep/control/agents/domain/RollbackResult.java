package top.yealike.pep.control.agents.domain;

import java.util.UUID;

public record RollbackResult(UUID releaseId, UUID publicationId, String version, long generation, String state) {}
