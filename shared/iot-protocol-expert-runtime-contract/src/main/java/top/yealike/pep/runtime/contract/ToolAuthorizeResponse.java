package top.yealike.pep.runtime.contract;

public record ToolAuthorizeResponse(boolean allowed, String code, String reason, String actorId, long authVersion) {}
