package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "EventView", description = "设备事件")
public record EventView(
        Long id, String eventId, String eventName, String level, String channelNo, Object content, String occurredAt) {}
