package top.yealike.pep.model.contract.gateway;

import java.util.UUID;

/** 额度账户启停结果。 */
public record QuotaAccountStateResult(UUID accountId, String state) {}
