package top.yealike.pep.ops.api.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DevicePatch", description = "设备运行属性增量")
public record DevicePatch(boolean allowWork, int maxPowerPercent) {}
