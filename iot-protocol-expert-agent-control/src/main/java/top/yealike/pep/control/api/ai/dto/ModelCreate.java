package top.yealike.pep.control.api.ai.dto;

public record ModelCreate(
        String key,
        String displayName,
        String providerCode,
        String upstreamModel,
        Integer contextWindow,
        Integer maxOutputTokens,
        String countingMode) {}
