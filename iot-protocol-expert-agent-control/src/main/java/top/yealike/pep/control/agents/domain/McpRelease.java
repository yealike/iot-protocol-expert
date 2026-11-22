package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import top.yealike.pep.runtime.contract.McpToolDescriptor;

/** 控制面领域记录。 */
public record McpRelease(
        UUID id,
        UUID componentId,
        String componentKey,
        String version,
        String transport,
        Map<String, Object> endpointPolicy,
        UUID credentialId,
        List<McpToolDescriptor> tools,
        String catalogDigest,
        String contentDigest,
        String state,
        long generation,
        Instant createdAt) {}
