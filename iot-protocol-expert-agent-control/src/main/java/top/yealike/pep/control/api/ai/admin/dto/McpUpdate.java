package top.yealike.pep.control.api.ai.admin.dto;

import java.util.Map;

public record McpUpdate(
        long expectedRevision,
        String displayName,
        String description,
        Map<String, Object> nativeRegistryRef,
        Map<String, Object> draft,
        boolean enabled) {}
