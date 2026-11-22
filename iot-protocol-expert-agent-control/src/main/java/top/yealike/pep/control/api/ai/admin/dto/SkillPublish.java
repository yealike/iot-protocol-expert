package top.yealike.pep.control.api.ai.admin.dto;

public record SkillPublish(String version, long expectedRevision, String idempotencyKey) {}
