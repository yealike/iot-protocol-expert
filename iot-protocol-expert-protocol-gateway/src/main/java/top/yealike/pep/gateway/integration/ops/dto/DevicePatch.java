package top.yealike.pep.gateway.integration.ops.dto;

/** 参数设置成功后的运行许可同步。 */
public record DevicePatch(boolean allowWork, int maxPowerPercent) {}
