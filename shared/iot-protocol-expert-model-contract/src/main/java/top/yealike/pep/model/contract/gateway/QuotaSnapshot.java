package top.yealike.pep.model.contract.gateway;

import java.util.List;

/** 主体额度快照。 */
public record QuotaSnapshot(
        String scopeKind,
        String subjectId,
        List<QuotaPolicyState> policies,
        long availableUnits,
        long pendingReconciliation) {

    public QuotaSnapshot {
        policies = policies == null ? List.of() : List.copyOf(policies);
    }
}
