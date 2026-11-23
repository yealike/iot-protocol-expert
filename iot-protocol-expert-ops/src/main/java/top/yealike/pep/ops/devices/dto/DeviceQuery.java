package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "DeviceQuery", description = "充电桩分页查询条件。8 位输入按设备 SN 精确查询，10 位输入按平台枪号查询。")
public record DeviceQuery(
        @Schema(description = "8 位设备 SN、10 位平台枪号或设备名称") String keyword,
        @Schema(description = "设备状态列表") List<String> states,
        @Schema(description = "所属产品 ID") Long productId,
        @Schema(description = "是否启用") Boolean enabled,
        @Schema(description = "从 0 开始的页码") Integer page,
        @Schema(description = "每页记录数") Integer size,
        @Schema(description = "排序字段；仅支持接口白名单字段") String sort) {}
