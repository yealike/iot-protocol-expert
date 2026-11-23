package top.yealike.pep.ops.access.domain;

import java.util.List;

public record Role(String code, String name, List<String> permissions) {}
