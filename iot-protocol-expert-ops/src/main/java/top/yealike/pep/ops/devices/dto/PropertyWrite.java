package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PropertyWrite", description = "属性写入请求")
public record PropertyWrite(
        @Schema(description = "物模型属性标识") String propertyId,
        @Schema(description = "枪号；非枪口属性可留空") String gunNo,
        @Schema(description = "要写入的属性值") Object value) {}
