package top.yealike.ipe.ai.contract;

public record ToolAuthorizeResponse(
        boolean allowed, String code, String reason, String actorId, long authVersion, String stationScope) {}
