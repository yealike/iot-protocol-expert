package top.yealike.pep.control.access.policy.domain;

import java.util.Set;

/** 一条按角色授权的策略行：允许使用的 Agent 与工具。 */
public record PolicyRule(String role, Set<String> agentKeys, Set<String> toolNames) {}
