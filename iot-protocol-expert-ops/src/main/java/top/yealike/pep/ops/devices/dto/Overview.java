package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeviceOverview", description = "充电桩统计总览")
public record Overview(long deviceTotal, long deviceOnline, long deviceFault, long productCount) {}
