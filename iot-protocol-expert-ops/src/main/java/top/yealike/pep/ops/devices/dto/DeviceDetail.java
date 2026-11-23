package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "DeviceDetail", description = "充电桩详情")
public record DeviceDetail(
        DeviceRow device,
        List<ChannelView> channels,
        List<PropertyValue> properties,
        List<EventView> events,
        List<CommandView> commands,
        List<ProtocolMappingView> protocolMappings) {}
