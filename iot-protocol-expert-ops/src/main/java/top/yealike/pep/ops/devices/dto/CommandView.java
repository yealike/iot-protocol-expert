package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CommandView", description = "设备指令记录")
public record CommandView(
        Long id,
        String commandType,
        String status,
        String requestPayload,
        String responsePayload,
        String operator,
        String createdAt,
        String sentAt,
        String ackAt) {}
