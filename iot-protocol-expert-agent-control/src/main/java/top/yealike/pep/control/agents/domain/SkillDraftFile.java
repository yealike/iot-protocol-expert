package top.yealike.pep.control.agents.domain;

import java.time.Instant;
import java.util.UUID;

/** 控制面领域记录。 */
public record SkillDraftFile(UUID id, UUID skillId, String path, String content, String digest, Instant updatedAt) {}
