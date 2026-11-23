package top.yealike.pep.simulator.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 模拟车辆参数。 */
@Schema(name = "VehicleRequest", description = "模拟车辆电池与卡号参数")
public record VehicleRequest(
        @Schema(description = "车辆电池容量，kWh", example = "60") double capacityKwh,
        @Schema(description = "初始电量百分比", example = "20") int soc,

        @Schema(description = "车辆 VIN", example = "LSVAA2A40NX000001")
        String vin,

        @Schema(description = "物理充电卡号", example = "00000000D14B0A54")
        String physicalCard) {}
