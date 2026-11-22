package top.yealike.pep.control.access.policy.domain;

public record Decision(boolean allowed, String reason, String policyVersion) {}
