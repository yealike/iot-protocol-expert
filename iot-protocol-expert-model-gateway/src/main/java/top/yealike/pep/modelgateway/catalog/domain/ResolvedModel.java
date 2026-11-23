package top.yealike.pep.modelgateway.catalog.domain;

/** 已解析的调用目标；secret 只在模型网关进程内使用，不得写入日志或响应。 */
public record ResolvedModel(ModelProfile profile, Provider provider, String secret) {}
