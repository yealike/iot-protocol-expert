package top.yealike.ipe.ai.contract;

public record RunMessage(String role, String content, String toolCallId, String toolResult) {}
