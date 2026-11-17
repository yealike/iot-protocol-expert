package top.yealike.pep.runtime.contract;

public record RunMessage(String role, String content, String toolCallId, String toolResult) {}
