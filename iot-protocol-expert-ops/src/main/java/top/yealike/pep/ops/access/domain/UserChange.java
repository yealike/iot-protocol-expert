package top.yealike.pep.ops.access.domain;

import java.util.List;

public record UserChange(String username, String password, boolean enabled, List<String> roles) {}
