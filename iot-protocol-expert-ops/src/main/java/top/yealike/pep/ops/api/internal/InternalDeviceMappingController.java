package top.yealike.pep.ops.api.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.ops.devices.dto.DeviceDtos;
import top.yealike.pep.ops.devices.service.DeviceProtocolMappingService;

/** protocol-gateway 启动时完整加载设备身份映射。 */
@RestController
@RequestMapping("/internal/device-mappings")
public class InternalDeviceMappingController {

    private final DeviceProtocolMappingService mappings;

    public InternalDeviceMappingController(DeviceProtocolMappingService mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    public DeviceDtos.DeviceMappingPage page(
            @RequestParam(required = false) Long afterId,
            @RequestParam(required = false, defaultValue = "200") Integer limit) {
        return mappings.page(afterId, limit);
    }
}
