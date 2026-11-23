package top.yealike.pep.modelgateway.quota.domain;

import java.util.UUID;

/** 一次预占的持久状态行。 */
public record Reservation(UUID id, UUID modelCallId, long reservedUnits, String state) {}
