package top.yealike.pep.modelgateway.quota.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 结算后追加用量调整的 HTTP 请求体。 */
@Schema(name = "AdjustmentRequest", description = "结算后追加用量调整的请求体；只追加记录，不修改原始账本")
public record AdjustmentRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID modelCallId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long deltaUnits,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
        String operatorId) {}
