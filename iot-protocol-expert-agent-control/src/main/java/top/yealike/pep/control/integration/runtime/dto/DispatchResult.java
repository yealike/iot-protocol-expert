package top.yealike.pep.control.integration.runtime.dto;

import top.yealike.pep.runtime.contract.RunDispatchReceipt;

public record DispatchResult(boolean accepted, int status, RunDispatchReceipt receipt, String code, String message) {}
