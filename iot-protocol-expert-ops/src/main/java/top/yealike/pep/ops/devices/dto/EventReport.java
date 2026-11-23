package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "EventReport", description = "设备事件上报")
public record EventReport(String eventId, String gunNo, String level, Map<String, Object> content, String occurredAt) {}
