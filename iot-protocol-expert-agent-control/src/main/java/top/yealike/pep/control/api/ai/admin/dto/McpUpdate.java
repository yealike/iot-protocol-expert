package top.yealike.pep.control.api.ai.admin.dto;

import top.yealike.pep.control.agents.domain.McpDraft;
import top.yealike.pep.control.agents.domain.McpNativeRegistryRef;

public record McpUpdate(
        long expectedRevision,
        String displayName,
        String description,
        McpNativeRegistryRef nativeRegistryRef,
        McpDraft draft,
        boolean enabled) {}
