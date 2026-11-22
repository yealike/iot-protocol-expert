package top.yealike.pep.control.api.ai.admin.dto;

import java.util.Map;

public record McpCreate(
        String key,
        String displayName,
        String description,
        String origin,
        Map<String, Object> nativeRegistryRef,
        Map<String, Object> draft) {}
