package top.yealike.pep.ops.access.domain;

public record IdentityRow(long id, String username, boolean enabled, long authVersion) {}
