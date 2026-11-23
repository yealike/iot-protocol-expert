package top.yealike.pep.control.agents.domain;

import top.yealike.pep.runtime.contract.RunUsage;

/** 模型连通性测试结果。 */
public record ModelTestResult(
        boolean success, String model, Long latencyMs, RunUsage usage, String note, String errorCode, String message) {}
