package top.yealike.pep.ops.devices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ProtocolMappingsSave", description = "保存充电枪第三方协议映射")
public record ProtocolMappingsSave(List<ProtocolMappingInput> mappings) {}
