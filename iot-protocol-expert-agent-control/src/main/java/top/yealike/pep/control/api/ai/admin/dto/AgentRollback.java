package top.yealike.pep.control.api.ai.admin.dto;

public record AgentRollback(String targetVersion, long expectedRevision, String idempotencyKey) {}
