package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "StartRequest", description = "设备启动充电申请")
public record StartRequest(
        String protocol,
        String protocolDeviceId,
        String gunNo,
        int startType,
        String physicalCard,
        String logicalCard,
        String account) {}
