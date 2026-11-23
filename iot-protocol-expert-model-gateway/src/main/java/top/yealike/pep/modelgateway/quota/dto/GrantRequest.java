package top.yealike.pep.modelgateway.quota.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 分配或调整额度的 HTTP 请求体；幂等键由 Idempotency-Key 头提供。 */
@Schema(name = "GrantRequest", description = "分配或调整额度的请求体；幂等键由 Idempotency-Key 头提供")
public record GrantRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String scopeKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String subjectId,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"TOTAL", "ON_FIRST_USE_WINDOW"})
        String mode,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long limitUnits,
        Long durationSeconds,
        String policyKey,
        String reason,
        String actorId,
        @Schema(hidden = true) String idempotencyKey) {}
