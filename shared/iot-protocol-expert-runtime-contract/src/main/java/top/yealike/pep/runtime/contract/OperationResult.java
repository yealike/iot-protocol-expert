package top.yealike.pep.runtime.contract;

/** 无响应正文的业务操作结果。 */
public record OperationResult(boolean ok, String status) {}
