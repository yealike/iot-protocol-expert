package top.yealike.pep.control.api.ai.admin.dto;

public record AgentPublish(String version, long expectedRevision, String idempotencyKey) {}
