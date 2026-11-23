package top.yealike.pep.modelgateway.quota.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 不可变额度策略版本；窗口模式从首次预占开始计时。 */
public record Policy(
        UUID id,
        String policyKey,
        long revision,
        String mode,
        long limitUnits,
        Long durationSeconds,
        OffsetDateTime effectiveAt,
        OffsetDateTime expiresAt) {}
