package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "DeviceMappingConnector", description = "Gateway 启动加载使用的充电枪映射")
public record DeviceMappingConnector(String platformGunNo, String protocolConnectorNo) {}
