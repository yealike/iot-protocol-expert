package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "SeriesView", description = "属性时序")
public record SeriesView(
        Long deviceId, String propertyId, String gunNo, String name, String unit, List<SeriesPoint> points) {}
