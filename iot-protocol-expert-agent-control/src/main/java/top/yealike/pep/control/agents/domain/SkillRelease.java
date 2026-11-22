package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;
import top.yealike.pep.runtime.contract.SkillBundle;

/** 控制面领域记录。 */
public record SkillRelease(
        UUID id,
        UUID skillId,
        String componentKey,
        String version,
        String name,
        String description,
        SkillBundle bundle,
        String bundleDigest,
        String state,
        long generation,
        Instant createdAt) {}
