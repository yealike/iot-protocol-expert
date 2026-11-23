package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "PropertyBatchWrite", description = "属性批量写入请求")
public record PropertyBatchWrite(List<PropertyWrite> values, String collectedAt) {}
