package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.AgentManifest;

/** 控制面领域记录。 */
public record AgentRelease(
        UUID id,
        UUID agentId,
        String agentKey,
        String version,
        AgentManifest manifest,
        String manifestDigest,
        String state,
        long generation,
        long releaseCursor,
        Instant createdAt) {}
