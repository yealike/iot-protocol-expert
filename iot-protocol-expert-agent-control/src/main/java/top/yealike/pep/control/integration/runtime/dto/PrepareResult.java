package top.yealike.pep.control.integration.runtime.dto;

public record PrepareResult(
        boolean ready, String state, String instanceId, String errorCode, String errorMessage, int status) {}
