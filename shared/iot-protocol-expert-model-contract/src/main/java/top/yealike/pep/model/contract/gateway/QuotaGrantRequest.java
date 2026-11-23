package top.yealike.pep.model.contract.gateway;

/** 分配或调整额度的请求体；幂等键由 Idempotency-Key 头提供。 */
public record QuotaGrantRequest(
        String scopeKind,
        String subjectId,
        String mode,
        long limitUnits,
        Long durationSeconds,
        String policyKey,
        String reason,
        String actorId) {}
