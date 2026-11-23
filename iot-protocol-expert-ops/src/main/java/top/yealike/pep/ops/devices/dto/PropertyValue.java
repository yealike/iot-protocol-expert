package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PropertyValue", description = "设备属性值")
public record PropertyValue(
        String propertyId,
        String name,
        String channelNo,
        String valueType,
        Object value,
        String display,
        String unit,
        String updatedAt,
        boolean writable,
        boolean channelScoped) {}
