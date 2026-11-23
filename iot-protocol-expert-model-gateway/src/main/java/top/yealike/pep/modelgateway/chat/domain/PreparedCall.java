package top.yealike.pep.modelgateway.chat.domain;

import top.yealike.pep.modelgateway.catalog.domain.ResolvedModel;
import top.yealike.pep.modelgateway.quota.domain.ReservationHandle;

/** 已完成额度预占、可向上游派发的调用。 */
public record PreparedCall(
        ResolvedModel model, ReservationHandle reservation, String upstreamBody, boolean stream, int maxOutputTokens) {}
