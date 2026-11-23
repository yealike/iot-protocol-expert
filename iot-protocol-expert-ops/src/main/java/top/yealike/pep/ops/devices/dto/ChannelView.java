package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChannelView", description = "充电枪实时通道")
public record ChannelView(
        String gunNo,
        String platformGunNo,
        String protocolConnectorNo,
        String name,
        boolean enabled,
        List<PropertyValue> properties) {}
