package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "ProductSave", description = "充电桩产品创建或更新请求")
public record ProductSave(
        @Schema(description = "产品编码") String productCode,
        @Schema(description = "产品名称") String name,
        @Schema(description = "产品分类编码") String categoryCode,
        @Schema(description = "关联协议文档 ID") Long protocolDocumentId,
        @Schema(description = "通信传输方式") String transport,
        @Schema(description = "厂商") String vendor,
        @Schema(description = "型号") String model,
        @Schema(description = "标准枪数") Integer connectorCount,
        @Schema(description = "产品元数据") Map<String, Object> metadata,
        @Schema(description = "产品配置") Map<String, Object> configuration,
        @Schema(description = "产品说明") String description,
        @Schema(description = "产品状态") String state) {}
