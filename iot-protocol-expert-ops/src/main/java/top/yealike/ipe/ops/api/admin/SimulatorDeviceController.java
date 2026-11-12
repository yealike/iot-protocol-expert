package top.yealike.ipe.ops.api.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import top.yealike.ipe.ops.device.dto.DeviceDtos;
import top.yealike.ipe.ops.device.entity.DeviceInstance;
import top.yealike.ipe.ops.device.service.DeviceInstanceService;
import top.yealike.ipe.ops.service.DeviceService;

/**
 * 模拟器只读设备目录。由模拟器令牌保护，不暴露给普通会话。
 *
 * <p>列表走 {@link DeviceInstanceService#rows(List)} 的批量视图： 站点、产品、标签、属性、通道计数各查一次，而不是每台设备查四次的 N+1。
 * 远程库上一次往返 100 ms 级，11 台设备就能差出好几秒。
 */
@RestController
@RequestMapping("/api/ops/simulator/devices")
public class SimulatorDeviceController {

    private final DeviceService devices;
    private final DeviceInstanceService instances;

    public SimulatorDeviceController(DeviceService devices, DeviceInstanceService instances) {
        this.devices = devices;
        this.instances = instances;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) String protocol,
            @RequestParam(required = false) String protocolDeviceId) {
        List<DeviceInstance> candidates = devices.simulatorDevices().stream()
                .filter(device -> stationId == null || stationId.equals(device.stationId))
                .filter(device -> sn == null
                        || sn.isBlank()
                        || device.deviceCode.toLowerCase().contains(sn.toLowerCase()))
                .filter(device -> protocol == null
                        || protocol.isBlank()
                        || DeviceService.normalizeProtocol(device.protocol)
                                .equals(DeviceService.normalizeProtocol(protocol)))
                .filter(device -> protocolDeviceId == null
                        || protocolDeviceId.isBlank()
                        || device.protocolDeviceId.contains(protocolDeviceId))
                .toList();
        return instances.rows(candidates).stream().map(this::row).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        DeviceInstance device = devices.get(id);
        if (!"CHARGER".equals(device.deviceType) || !device.enabled || !device.allowSimulate) {
            throw new IllegalArgumentException("设备不可用于模拟: " + device.deviceCode);
        }
        return row(instances.view(device));
    }

    private Map<String, Object> row(DeviceDtos.DeviceRow view) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", view.id());
        // 模拟器侧字段名保留 sn，语义即平台设备编码
        row.put("sn", view.deviceCode());
        row.put("name", view.name());
        row.put("deviceType", view.deviceType());
        row.put("stationId", view.stationId());
        row.put("stationName", view.stationName());
        row.put("protocol", view.protocol());
        row.put("protocolDeviceId", view.protocolDeviceId());
        row.put("gunCount", view.gunCount());
        row.put("online", "ONLINE".equals(view.state()) || "FAULT".equals(view.state()));
        row.put("state", view.state());
        row.put("enabled", view.enabled());
        row.put("allowSimulate", view.allowSimulate());
        return row;
    }
}
