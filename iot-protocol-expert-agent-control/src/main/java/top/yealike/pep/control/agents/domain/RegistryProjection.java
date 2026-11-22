package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 控制面领域记录。 */
public record RegistryProjection(
        UUID id,
        String resourceType,
        UUID releaseId,
        String serviceName,
        String providerId,
        String desiredState,
        String observedState,
        String ownerNode,
        Instant lastSuccessAt,
        String lastError) {}
