package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SeriesPoint", description = "属性时序点")
public record SeriesPoint(String time, Double number, String text) {}
