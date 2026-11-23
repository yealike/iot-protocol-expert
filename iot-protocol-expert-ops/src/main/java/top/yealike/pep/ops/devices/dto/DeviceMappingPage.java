package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "DeviceMappingPage", description = "Gateway 设备映射分页")
public record DeviceMappingPage(
        List<DeviceMappingRow> content, boolean hasMore, Long nextAfterId, String snapshotToken) {}
