package top.yealike.pep.modelgateway.chat.domain;

/** 非流式上游响应；body 原样转发给调用方。 */
public record ProviderResponse(int status, String contentType, String body) {}
