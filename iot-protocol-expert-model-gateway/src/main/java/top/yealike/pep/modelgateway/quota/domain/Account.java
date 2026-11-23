package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 额度账户；以 (scopeKind, subjectId, policyKey) 唯一定位。 */
public record Account(
        UUID id,
        String scopeKind,
        String subjectId,
        String policyKey,
        long activeRevision,
        UUID currentBucketId,
        String state,
        long debtUnits,
        long version) {}
