package top.yealike.pep.control.access.policy.domain;

import java.util.List;

public record PolicySnapshot(List<PolicyRule> rules, String version) {}
