package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(name = "ProductDetail", description = "产品档案与物模型")
public record ProductDetail(ProductRow product, Map<String, Object> metadata, Map<String, Object> configuration) {}
