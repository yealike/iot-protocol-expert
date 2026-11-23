package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProductRow", description = "充电桩产品列表项")
public record ProductRow(
        Long id,
        String productCode,
        String name,
        String categoryCode,
        String protocol,
        Long protocolDocumentId,
        String protocolEnglishName,
        String protocolVersion,
        String transport,
        String vendor,
        String model,
        int connectorCount,
        String description,
        String state,
        long deviceCount,
        int propertyCount,
        int functionCount,
        int eventCount) {}
