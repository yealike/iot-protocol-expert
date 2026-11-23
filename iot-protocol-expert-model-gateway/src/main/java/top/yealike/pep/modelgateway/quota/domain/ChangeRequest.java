package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 额度授予/停用的幂等变更记录。 */
public record ChangeRequest(UUID accountId, Long newRevision, String action, String effectiveMode, String reason) {}
