package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 注册投影诊断行。 */
public record RegistryComponentView(
        UUID id,
        String resourceType,
        UUID releaseId,
        String serviceName,
        String providerId,
        String desiredState,
        String observedState,
        String ownerNode,
        Instant lastSuccessAt,
        String lastError,
        boolean healthy,
        String componentKey,
        String componentVersion) {}
