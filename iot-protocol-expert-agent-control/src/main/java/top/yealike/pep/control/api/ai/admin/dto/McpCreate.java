package top.yealike.pep.control.api.ai.admin.dto;

import top.yealike.pep.control.agents.domain.McpDraft;
import top.yealike.pep.control.agents.domain.McpNativeRegistryRef;

public record McpCreate(
        String key,
        String displayName,
        String description,
        String origin,
        McpNativeRegistryRef nativeRegistryRef,
        McpDraft draft) {}
