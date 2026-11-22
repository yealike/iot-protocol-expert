package top.yealike.pep.control.api.control.dto;

public record GrantRequest(
        String scopeKind,
        String subjectId,
        String mode,
        long limitUnits,
        Long durationSeconds,
        String policyKey,
        String reason,
        String actorId) {}
