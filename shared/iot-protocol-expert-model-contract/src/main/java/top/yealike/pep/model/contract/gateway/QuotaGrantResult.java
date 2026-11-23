package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 额度分配结果。 */
public record QuotaGrantResult(UUID accountId, Long revision, String mode, String policyKey, boolean replayed) {}
