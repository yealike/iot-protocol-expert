package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StatusRequest", description = "枪口状态上报")
public record StatusRequest(String protocolDeviceId, int connectorId, String status, String errorCode) {}
