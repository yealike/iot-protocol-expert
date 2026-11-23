package top.yealike.pep.ops.api.internal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.devices.dto.DeviceMappingPage;
import top.yealike.pep.ops.devices.service.DeviceProtocolMappingService;

/** protocol-gateway 启动时完整加载设备身份映射。 */
@RestController
@RequestMapping(path = "/internal/device-mappings", produces = "application/json")
@Tag(name = "内部设备映射", description = "协议网关启动时全量加载第三方设备身份映射")
@SecurityRequirement(name = "opsServiceToken")
public class InternalDeviceMappingController {

    private final DeviceProtocolMappingService mappings;

    public InternalDeviceMappingController(DeviceProtocolMappingService mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    @Operation(operationId = "internalPageDeviceMappings", summary = "分页查询设备映射")
    public DeviceMappingPage page(
            @Parameter(description = "上一页最后一个映射 ID") @RequestParam(required = false) Long afterId,
            @Parameter(description = "每页条数，默认 200") @RequestParam(required = false, defaultValue = "200")
                    Integer limit) {
        return mappings.page(afterId, limit);
    }
}
